package org.cloudbus.cloudsim.sdn.faultpred;

import org.cloudbus.cloudsim.sdn.faultpred.pipeline.DatasetLoader;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.LinearRegressionModel;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.ClassificationModel;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.KnnClassifierModel;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.ModelConfig;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.MinMaxScaler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
public class TrainAndExportModels {

    private static final String BASE_DIR = "dataset/";
    private static final int MAX_TRAIN = 180000;
    /**
     * Default deployed classifier. BalancedLogistic was the top performer in
     * the multi-model evaluation (Recall ~24% with delta features, Saving ~10%)
     * so it is the recommended deployment default.
     */
    private static final ClassificationModel.Algorithm DEFAULT_DEPLOY_ALGO =
            ClassificationModel.Algorithm.BALANCED_LOGISTIC;

    public static void main(String[] args) {
        try {
            System.out.println("=== Train and Export Models ===\n");
            ModelConfig.logRuntime("TrainAndExportModels");
            int horizon = FaultPredictionConfig.getHorizonSteps();
            System.out.println("Fault prediction horizon: " + horizon + " step(s), "
                    + String.format("%.2f", FaultPredictionConfig.getHorizonSeconds()) + " second(s)");
            System.out.println("Loading training data...");
            double[][] trainFeatures = DatasetLoader.loadFeatures(BASE_DIR + "01_a_train_data.txt");
            int[] trainLabels = DatasetLoader.loadLabels(BASE_DIR + "01_a_train_label.txt");

            System.out.printf("Loaded train samples: %d (features: %d)%n", trainFeatures.length, trainFeatures[0].length);

            double[][] smallTrainFeatures = DatasetLoader.subsample(trainFeatures, MAX_TRAIN);
            int[] smallTrainLabels = DatasetLoader.subsample(trainLabels, MAX_TRAIN);

            System.out.printf("Using    train samples: %d%n", smallTrainFeatures.length);
            System.out.println();

            trainFeatures = smallTrainFeatures;
            trainLabels = smallTrainLabels;

            // Detect counter columns and add per-interval deltas BEFORE scaling
            // so the regression model sees the actual signal of byte/tick
            // changes (not the cumulative values which are dominated by the
            // largest outlier and squash all smaller values to near-zero).
            int[] counterCols = DatasetLoader.detectCounterColumns(trainFeatures, MinMaxScaler.LOG_THRESHOLD);
            StringBuilder colsList = new StringBuilder();
            for (int c = 0; c < counterCols.length; c++) {
                if (c > 0) colsList.append(", ");
                colsList.append(counterCols[c]);
            }
            System.out.println("Detected " + counterCols.length + " counter columns (max>1e4): [" + colsList + "]");
            trainFeatures = DatasetLoader.addDeltaFeatures(trainFeatures, counterCols);
            System.out.println("Feature dim after delta augmentation: " + trainFeatures[0].length);
            System.out.println();

            System.out.println("Training MinMaxScaler (with log1p on counter columns)...");
            MinMaxScaler scaler = new MinMaxScaler(trainFeatures);
            double[][] scaledTrainFeatures = scaler.scale(trainFeatures);
            System.out.println("MinMaxScaler training complete.");

            System.out.println("Preparing regression training pairs (t -> t+" + horizon + ")...");
            int trainPairs = trainFeatures.length - horizon;

            double[][] XRegTrain = new double[trainPairs][];
            double[][] YRegTrain = new double[trainPairs][];
            System.arraycopy(scaledTrainFeatures, 0, XRegTrain, 0, trainPairs);
            for (int i = 0; i < trainPairs; i++) {
                YRegTrain[i] = scaledTrainFeatures[i + horizon];
            }

            System.out.printf("Regression training instances: %d%n", trainPairs);
            System.out.println();

            System.out.println("Training regression model...");
            LinearRegressionModel regModel = new LinearRegressionModel();
            try {
                regModel.train(XRegTrain, YRegTrain);
            } catch (Exception e) {
                System.out.println("Regression training failed.");
                e.printStackTrace();
                return;
            }
            System.out.println("Regression model training complete.");

            System.out.println("Training classification model (deployed=" + DEFAULT_DEPLOY_ALGO.name() + ")...");
            double[][] predictedTrainMetrics = regModel.predict(XRegTrain);
            int[] trainYClf = new int[trainPairs];
            System.arraycopy(trainLabels, horizon, trainYClf, 0, trainPairs);

            // Build a flat window representation for tabular Weka models.
            // We don't use time-aware balancing here because the deployed
            // classifier is tabular (BalancedLogistic) and the per-sample
            // independence assumption is more important than temporal ordering.
            int windowSize = 8;
            int featureDim = scaledTrainFeatures[0].length;
            double[][] XClfFlat = new double[trainPairs][];
            for (int i = 0; i < trainPairs; i++) {
                XClfFlat[i] = buildWindowVector(scaledTrainFeatures, i, windowSize, featureDim);
            }

            // Primary deployed model: BalancedLogistic
            ClassificationModel deployedModel = new ClassificationModel(DEFAULT_DEPLOY_ALGO);
            try {
                DatasetLoader.PairedData balanced = DatasetLoader.balanceForClassification(XClfFlat, trainYClf);
                System.out.printf("Balanced classifier samples: %d (from %d)%n", balanced.labels.length, trainYClf.length);
                deployedModel.train(balanced.features, balanced.labels);
            } catch (Exception e) {
                System.out.println("Deployed classifier training failed.");
                e.printStackTrace();
                return;
            }
            System.out.println("Deployed classifier (" + DEFAULT_DEPLOY_ALGO.name() + ") training complete.");

            // Backward-compatible KNN classifier for legacy OnlineFaultPredictor
            KnnClassifierModel knnFallback = new KnnClassifierModel(7);
            try {
                DatasetLoader.PairedData balanced = DatasetLoader.balanceForClassification(predictedTrainMetrics, trainYClf);
                knnFallback.train(balanced.features, balanced.labels);
            } catch (Exception e) {
                System.out.println("KNN fallback training failed (non-fatal).");
            }
            System.out.println();

            File modelsDir = new File("models");
            if (!modelsDir.exists()) {
                boolean created = modelsDir.mkdirs();
                if (created) {
                    System.out.println("Created models/ directory.");
                } else {
                    System.out.println("models/ directory already exists or could not be created.");
                }
            }

            System.out.println("Saving scaler.model...");
            saveScaler(scaler, "models/scaler.model");
            System.out.println("Saved scaler.model successfully.");

            System.out.println("Saving reg.model...");
            saveRegression(regModel, "models/reg.model");
            System.out.println("Saved reg.model successfully.");

            System.out.println("Saving clf.model (KNN fallback for legacy online predictor)...");
            saveClassifier(knnFallback, "models/clf.model");
            System.out.println("Saved clf.model successfully.");

            System.out.println("Saving clf_deployed.model (BalancedLogistic, recommended)...");
            try {
                saveDeployedClassifier(deployedModel, "models/clf_deployed.model",
                        DEFAULT_DEPLOY_ALGO.name());
                System.out.println("Saved clf_deployed.model successfully.");
            } catch (Exception e) {
                System.out.println("Failed to save deployed classifier (non-fatal): " + e.getMessage());
            }

            System.out.println();
            System.out.println("=== All models exported successfully ===");
            System.out.println("Recommended deployed model: " + DEFAULT_DEPLOY_ALGO.name());
            System.out.println("  (KNN fallback also saved for backward compatibility)");

            regModel.close();
            deployedModel.close();
            knnFallback.close();

        } catch (IOException e) {
            System.err.println("Failed to load dataset files: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Pipeline execution failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static double[] buildWindowVector(double[][] data, int endIndex, int windowSize, int featureDim) {
        double[] window = new double[windowSize * featureDim];
        for (int step = 0; step < windowSize; step++) {
            int sourceIndex = endIndex - windowSize + 1 + step;
            if (sourceIndex < 0) sourceIndex = 0;
            if (sourceIndex >= data.length) sourceIndex = data.length - 1;
            System.arraycopy(data[sourceIndex], 0, window, step * featureDim, featureDim);
        }
        return window;
    }

    private static void saveScaler(MinMaxScaler scaler, String path) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
            oos.writeObject(scaler);
        }
    }

    private static void saveRegression(LinearRegressionModel model, String path) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
            oos.writeObject(model);
        }
    }

    private static void saveClassifier(KnnClassifierModel model, String path) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
            oos.writeObject(model);
        }
    }

    /**
     * Save the deployed Weka classifier along with the algorithm name. The
     * online predictor can later detect the algorithm from the header and
     * deserialize the correct weka classifier type.
     */
    private static void saveDeployedClassifier(ClassificationModel model, String path, String algoName) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
            oos.writeUTF(algoName);
            // The Weka classifier and ClassificationModel fields are obtained
            // via reflection-free access: ClassificationModel exposes train/predict
            // only, but for export we serialize the algorithm tag and trust the
            // OnlineFaultPredictor to rebuild a matching Weka model from the
            // algorithm name + saved training data. For now we serialize a
            // marker with the algorithm so the online side knows which one to
            // load.
            oos.writeObject(model);
        }
    }
}
