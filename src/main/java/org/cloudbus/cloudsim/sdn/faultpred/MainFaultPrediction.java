package org.cloudbus.cloudsim.sdn.faultpred;

import org.cloudbus.cloudsim.sdn.faultpred.eval.FaultCostModel;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ReportPrinter;
import org.cloudbus.cloudsim.sdn.faultpred.eval.Metrics;
import org.cloudbus.cloudsim.sdn.faultpred.eval.EvaluationResult;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.DatasetLoader;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.ClassificationModel;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.ModelConfig;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.MinMaxScaler;
import org.cloudbus.cloudsim.sdn.memory.FaultType;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostModel;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostResult;
import org.cloudbus.cloudsim.sdn.Configuration;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ReportSaver;

import java.io.IOException;

public class MainFaultPrediction {

    private static final String BASE_DIR = "dataset/";
    // Max records to use for training/testing. 0 = use all. Can be set via
    // system properties (-Dmax.train=10000 -Dmax.test=2000) or env vars MAX_TRAIN/MAX_TEST.
    private static final int MAX_TRAIN = parseEnvOrPropInt("max.train", "MAX_TRAIN", 0);
    private static final int MAX_TEST = parseEnvOrPropInt("max.test", "MAX_TEST", 0);
    private static final String MODEL_SELECTION_PROPERTY = "faultpred.models";
    private static final String MODEL_SELECTION_ENV = "FAULTPRED_MODELS";
    private static final String SINGLE_MODEL_PROPERTY = "faultpred.model";
    private static final String SINGLE_MODEL_ENV = "FAULTPRED_MODEL";
    // Sliding window size. With horizon=5 the model needs to see at least
    // horizon+epsilon past timesteps. Fault runs are 5 consecutive samples,
    // so a window of 20 covers the "buildup" before the fault and the fault
    // start. Configurable via -Dfaultpred.window=20 or FAULTPRED_WINDOW env.
    private static final String WINDOW_SIZE_PROPERTY = "faultpred.window";
    private static final String WINDOW_SIZE_ENV = "FAULTPRED_WINDOW";
    private static final int DEFAULT_WINDOW_SIZE = 20;
    private static final int WINDOW_SIZE = parseEnvOrPropInt(WINDOW_SIZE_PROPERTY, WINDOW_SIZE_ENV, DEFAULT_WINDOW_SIZE);
    // Class balancing mode. Four options:
    //   - "none"        : no balancing (full imbalanced data, useful as a baseline)
    //   - "undersample" : random undersample NONE to match fault count (default)
    //   - "timeaware"   : preserve time order, used for LSTM/GRU
    //   - "smote"       : SMOTE-style oversample minority classes
    // Configurable via -Dfaultpred.balance=undersample or FAULTPRED_BALANCE env.
    public enum BalanceMode { NONE, UNDERSAMPLE, TIMEAWARE, SMOTE }
    private static final String BALANCE_MODE_PROPERTY = "faultpred.balance";
    private static final String BALANCE_MODE_ENV = "FAULTPRED_BALANCE";
    private static final BalanceMode BALANCE_MODE = parseBalanceMode(
            firstNonEmpty(System.getProperty(BALANCE_MODE_PROPERTY), System.getenv(BALANCE_MODE_ENV)),
            BalanceMode.UNDERSAMPLE);

    private static BalanceMode parseBalanceMode(String v, BalanceMode defaultMode) {
        if (v == null || v.isEmpty()) return defaultMode;
        switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "none":
            case "off":
            case "no":
            case "false":
                return BalanceMode.NONE;
            case "undersample":
            case "under":
            case "us":
                return BalanceMode.UNDERSAMPLE;
            case "timeaware":
            case "time":
            case "ta":
                return BalanceMode.TIMEAWARE;
            case "smote":
            case "over":
            case "os":
                return BalanceMode.SMOTE;
            default:
                System.out.println("Warning: unknown balance mode '" + v + "', falling back to " + defaultMode);
                return defaultMode;
        }
    }

    private static int parseEnvOrPropInt(String propName, String envName, int defaultValue) {
        String v = System.getProperty(propName);
        if (v == null || v.isEmpty()) v = System.getenv(envName);
        if (v == null || v.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static ClassificationModel.Algorithm[] resolveAlgorithms() {
        String selection = firstNonEmpty(
                System.getProperty(SINGLE_MODEL_PROPERTY),
                System.getenv(SINGLE_MODEL_ENV),
                System.getProperty(MODEL_SELECTION_PROPERTY),
                System.getenv(MODEL_SELECTION_ENV));
        if (selection == null || selection.equalsIgnoreCase("all")) {
            return defaultAlgorithms();
        }

        String[] tokens = selection.split("[,;\\s]+");
        List<ClassificationModel.Algorithm> resolved = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            ClassificationModel.Algorithm algo = resolveModelToken(token);
            if (algo != null && !resolved.contains(algo)) {
                resolved.add(algo);
            } else if (algo == null) {
                System.out.println("Warning: unknown model token ignored: " + token);
            }
        }

        if (resolved.isEmpty()) {
            System.out.println("No valid model tokens found; falling back to all models.");
            return defaultAlgorithms();
        }

        List<ClassificationModel.Algorithm> clean = new ArrayList<>();
        for (ClassificationModel.Algorithm algo : resolved) {
            if (algo != null && !clean.contains(algo)) {
                clean.add(algo);
            }
        }
        return clean.toArray(new ClassificationModel.Algorithm[0]);
    }

    private static ClassificationModel.Algorithm resolveModelToken(String token) {
        if (token == null) return null;
        String normalized = normalizeToken(token);
        if (normalized.isEmpty()) return null;

        // Try direct enum name / label matches first
        for (ClassificationModel.Algorithm a : ClassificationModel.Algorithm.values()) {
            try {
                if (normalizeToken(a.name()).equals(normalized)) return a;
                if (a.label != null && normalizeToken(a.label).equals(normalized)) return a;
            } catch (Throwable t) {
                // ignore and continue
            }
        }

        // Backward-compatible synonyms
        switch (normalized) {
            case "KNN":
                return ClassificationModel.Algorithm.KNN;
            case "RF":
            case "RANDOMFOREST":
                return ClassificationModel.Algorithm.RANDOM_FOREST;
            case "LOGISTIC":
            case "LOGISTICREGRESSION":
                return ClassificationModel.Algorithm.LOGISTIC_REGRESSION;
            case "SVM":
                return ClassificationModel.Algorithm.SVM;
            case "GB":
                return ClassificationModel.Algorithm.GRADIENT_BOOSTING;
            case "LB":
            case "LIGHTBOOSTING":
                return ClassificationModel.Algorithm.LIGHT_BOOSTING;
            case "LSTM":
                return ClassificationModel.Algorithm.LSTM;
            case "GRU":
                return ClassificationModel.Algorithm.GRU;
            case "BRF":
            case "BALANCEDRF":
                return ClassificationModel.Algorithm.BALANCED_RANDOM_FOREST;
            case "BL":
            case "BALANCEDLOGISTIC":
                return ClassificationModel.Algorithm.BALANCED_LOGISTIC;
            default:
                return null;
        }
    }

    private static ClassificationModel.Algorithm[] defaultAlgorithms() {
        return new ClassificationModel.Algorithm[] {
            ClassificationModel.Algorithm.KNN,
            ClassificationModel.Algorithm.RANDOM_FOREST,
            ClassificationModel.Algorithm.LOGISTIC_REGRESSION,
            ClassificationModel.Algorithm.SVM,
            ClassificationModel.Algorithm.GRADIENT_BOOSTING,
            ClassificationModel.Algorithm.LIGHT_BOOSTING,
            ClassificationModel.Algorithm.LSTM,
            ClassificationModel.Algorithm.GRU,
            ClassificationModel.Algorithm.BALANCED_RANDOM_FOREST,
            ClassificationModel.Algorithm.BALANCED_LOGISTIC
        };
    }

    private static String normalizeToken(String token) {
        return token == null ? "" : token.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "");
    }

    private static String algoLabelOrName(ClassificationModel.Algorithm algo) {
        if (algo == null) return "null";
        try {
            if (algo.label != null && !algo.label.trim().isEmpty()) return algo.label;
        } catch (Throwable t) {
            // ignore - fall back to name()
        }
        return algo.name();
    }

    private static double[] buildWindowVector(double[][] data, int endIndex, int windowSize) {
        int featureDim = data[0].length;
        double[] window = new double[windowSize * featureDim];
        for (int step = 0; step < windowSize; step++) {
            int sourceIndex = endIndex - windowSize + 1 + step;
            if (sourceIndex < 0) sourceIndex = 0;
            if (sourceIndex >= data.length) sourceIndex = data.length - 1;
            System.arraycopy(data[sourceIndex], 0, window, step * featureDim, featureDim);
        }
        return window;
    }

    private static double[][] buildWindowMatrix(double[][] data, int endIndex, int windowSize) {
        int featureDim = data[0].length;
        double[][] window = new double[windowSize][featureDim];
        for (int step = 0; step < windowSize; step++) {
            int sourceIndex = endIndex - windowSize + 1 + step;
            if (sourceIndex < 0) sourceIndex = 0;
            if (sourceIndex >= data.length) sourceIndex = data.length - 1;
            System.arraycopy(data[sourceIndex], 0, window[step], 0, featureDim);
        }
        return window;
    }

    private static double[][][] reshapeWindows(double[][] flatWindows, int windowSize, int featureDim) {
        double[][][] windows = new double[flatWindows.length][windowSize][featureDim];
        for (int i = 0; i < flatWindows.length; i++) {
            for (int step = 0; step < windowSize; step++) {
                System.arraycopy(flatWindows[i], step * featureDim, windows[i][step], 0, featureDim);
            }
        }
        return windows;
    }

    public static void main(String[] args) {
        double mae = Double.NaN;
        double rmse = Double.NaN;
        try {
            System.out.println("=== CloudSim SDN Fault Prediction Pipeline (Multi-Model) ===\n");
            ModelConfig.logRuntime("MainFaultPrediction");
            int horizon = FaultPredictionConfig.getHorizonSteps();
            System.out.println("Fault prediction horizon: " + horizon + " step(s), "
                    + String.format("%.2f", FaultPredictionConfig.getHorizonSeconds()) + " second(s)");
            System.out.println("Loading dataset...");
            System.out.println("Train sets: " + DatasetLoader.describeDatasetFiles(BASE_DIR, "train"));
            System.out.println("Test  sets: " + DatasetLoader.describeDatasetFiles(BASE_DIR, "test"));
                System.out.println("Loading dataset (all matching train/test files in dataset/)...");
                System.out.println("Train files: " + DatasetLoader.describeDatasetFiles(BASE_DIR, "train"));
                double[][] trainFeatures = DatasetLoader.loadFeaturesFromDirectory(BASE_DIR, "train");
                int[] trainLabels = DatasetLoader.loadLabelsFromDirectory(BASE_DIR, "train");
                System.out.println("Test files : " + DatasetLoader.describeDatasetFiles(BASE_DIR, "test"));
                double[][] testFeatures = DatasetLoader.loadFeaturesFromDirectory(BASE_DIR, "test");
                int[] testLabels = DatasetLoader.loadLabelsFromDirectory(BASE_DIR, "test");

            int originalTrainCount = trainFeatures.length;
            int originalTestCount = testFeatures.length;
            int featureDim = (originalTrainCount > 0) ? trainFeatures[0].length : (originalTestCount > 0 ? testFeatures[0].length : 0);

            // Apply optional subsampling if MAX_TRAIN / MAX_TEST are set (>0)
            int usedTrain = originalTrainCount;
            int usedTest = originalTestCount;
            if (MAX_TRAIN > 0 && MAX_TRAIN < originalTrainCount) {
                double[][] smallTrainFeatures = DatasetLoader.subsample(trainFeatures, MAX_TRAIN);
                int[] smallTrainLabels = DatasetLoader.subsample(trainLabels, MAX_TRAIN);
                trainFeatures = smallTrainFeatures;
                trainLabels = smallTrainLabels;
                usedTrain = trainFeatures.length;
            }
            if (MAX_TEST > 0 && MAX_TEST < originalTestCount) {
                double[][] smallTestFeatures = DatasetLoader.subsample(testFeatures, MAX_TEST);
                int[] smallTestLabels = DatasetLoader.subsample(testLabels, MAX_TEST);
                testFeatures = smallTestFeatures;
                testLabels = smallTestLabels;
                usedTest = testFeatures.length;
            }

            System.out.printf("Loaded train samples: %d (features: %d)%n", originalTrainCount, featureDim);
            System.out.printf("Loaded test  samples: %d (features: %d)%n", originalTestCount, featureDim);
            System.out.printf("Using    train samples: %d (max=%d)%n", usedTrain, MAX_TRAIN);
            System.out.printf("Using    test  samples: %d (max=%d)%n", usedTest, MAX_TEST);
            System.out.println();

            // Detect counter columns (raw value > 1e4) and add per-interval deltas
            // as additional features. Cumulative counters carry no fault signal
            // by themselves, but a sudden drop or stall is highly informative.
            int[] counterCols = DatasetLoader.detectCounterColumns(trainFeatures, MinMaxScaler.LOG_THRESHOLD);
            StringBuilder colsList = new StringBuilder();
            for (int c = 0; c < counterCols.length; c++) {
                if (c > 0) colsList.append(", ");
                colsList.append(counterCols[c]);
            }
            System.out.println("Detected " + counterCols.length + " counter columns (max>1e4): [" + colsList + "]");
            trainFeatures = DatasetLoader.addDeltaFeatures(trainFeatures, counterCols);
            testFeatures = DatasetLoader.addDeltaFeatures(testFeatures, counterCols);
            featureDim = trainFeatures[0].length;
            System.out.println("Feature dim after delta augmentation: " + featureDim);
            System.out.println();

            MinMaxScaler scaler = new MinMaxScaler(trainFeatures);
            double[][] scaledTrainFeatures = scaler.scale(trainFeatures);
            double[][] scaledTestFeatures = scaler.scale(testFeatures);

            int trainPairs = Math.max(0, trainFeatures.length - horizon);
            int testPairs = Math.max(0, testFeatures.length - horizon);
            int windowSize = WINDOW_SIZE;
            if (windowSize < horizon + 1) {
                System.out.println("WARNING: windowSize=" + windowSize + " is smaller than horizon+1="
                        + (horizon + 1) + "; raising to " + (horizon + 1));
                windowSize = horizon + 1;
            }
            System.out.println("Sliding window size: " + windowSize + " timestep(s) "
                    + "(" + (windowSize * FaultPredictionConfig.getHorizonSeconds()) + " second(s) of context)");
            System.out.println("Balance mode: " + BALANCE_MODE
                    + "  (none|undersample|timeaware|smote via -Dfaultpred.balance=MODE)");

            double[][] XTrainFlat = new double[trainPairs][];
            double[][][] XTrainSeq = new double[trainPairs][][];
            int[] trainYClf = new int[trainPairs];
            for (int i = 0; i < trainPairs; i++) {
                int endIndex = i;
                XTrainFlat[i] = buildWindowVector(scaledTrainFeatures, endIndex, windowSize);
                XTrainSeq[i] = buildWindowMatrix(scaledTrainFeatures, endIndex, windowSize);
                trainYClf[i] = trainLabels[i + horizon];
            }

            double[][] XTestFlat = new double[testPairs][];
            double[][][] XTestSeq = new double[testPairs][][];
            int[] YClfTest = new int[testPairs];
            for (int i = 0; i < testPairs; i++) {
                int endIndex = i;
                XTestFlat[i] = buildWindowVector(scaledTestFeatures, endIndex, windowSize);
                XTestSeq[i] = buildWindowMatrix(scaledTestFeatures, endIndex, windowSize);
                YClfTest[i] = testLabels[i + horizon];
            }

            System.out.printf("Classification training instances: %d%n", trainPairs);
            System.out.printf("Classification test     instances: %d%n", testPairs);
            System.out.println();

            FaultCostModel costModel = new FaultCostModel();
            ReportPrinter.printCostModel(costModel);

            ClassificationModel.Algorithm[] algorithms = resolveAlgorithms();
            System.out.print("Selected models: ");
            boolean firstModel = true;
            for (ClassificationModel.Algorithm algo : algorithms) {
                if (algo == null) {
                    continue;
                }
                if (!firstModel) System.out.print(", ");
                System.out.print(algoLabelOrName(algo));
                firstModel = false;
            }
            System.out.println();

            java.util.List<EvaluationResult> results = new java.util.ArrayList<>();
            for (ClassificationModel.Algorithm algo : algorithms) {
                if (algo == null) {
                    System.out.println("Skipping null model entry.");
                    continue;
                }
                System.out.printf("%n=== Training %s ===%n", algoLabelOrName(algo));
                ClassificationModel clfModel = new ClassificationModel(algo);
                try {
                    // Pick the balancing strategy based on the configured
                    // BalanceMode and the algorithm family. LSTM/GRU benefit
                    // from time-aware ordering; tabular models use the
                    // generic random undersampling. SMOTE/NONE are also
                    // available for ablation studies.
                    DatasetLoader.PairedData balanced;
                    boolean isRecurrent = (algo == ClassificationModel.Algorithm.LSTM
                            || algo == ClassificationModel.Algorithm.GRU);
                    switch (BALANCE_MODE) {
                        case NONE:
                            balanced = new DatasetLoader.PairedData(XTrainFlat, trainYClf);
                            break;
                        case TIMEAWARE:
                            balanced = DatasetLoader.balanceForClassificationTimeAware(XTrainFlat, trainYClf);
                            break;
                        case SMOTE:
                            // SMOTE is applied inside the LSTM/GRU training
                            // path via RecurrentFaultClassifier. For tabular
                            // models we fall back to undersampling.
                            if (isRecurrent) {
                                // Pass the full data; the recurrent trainer
                                // will run its own SMOTE inside trainWindows.
                                balanced = new DatasetLoader.PairedData(XTrainFlat, trainYClf);
                            } else {
                                balanced = DatasetLoader.balanceForClassification(XTrainFlat, trainYClf);
                            }
                            break;
                        case UNDERSAMPLE:
                        default:
                            balanced = DatasetLoader.balanceForClassification(XTrainFlat, trainYClf);
                            break;
                    }
                    System.out.printf("Balanced training set: %d samples (from %d)%n", balanced.labels.length, trainYClf.length);
                    if (algo == ClassificationModel.Algorithm.LSTM || algo == ClassificationModel.Algorithm.GRU) {
                        clfModel.trainWindows(reshapeWindows(balanced.features, windowSize, featureDim), balanced.labels);
                    } else {
                        clfModel.train(balanced.features, balanced.labels);
                    }
                } catch (Exception e) {
                    System.out.println("Classification training failed for " + algoLabelOrName(algo));
                    e.printStackTrace();
                    clfModel.close();
                    continue;
                }

                System.out.println("Running classification predictions on test set...");
                int[] predictedLabels;
                if (algo == ClassificationModel.Algorithm.LSTM || algo == ClassificationModel.Algorithm.GRU) {
                    predictedLabels = clfModel.predictWindows(XTestSeq);
                } else {
                    predictedLabels = clfModel.predict(XTestFlat);
                }

                EvaluationResult result = Metrics.computeClassificationMetrics(YClfTest, predictedLabels);
                result.modelName = algoLabelOrName(algo);
                result.algorithm = algo;
                result.regressionMAE = Double.NaN;
                result.regressionRMSE = Double.NaN;

                long[] faultCounts = new long[6];
                for (int lbl : YClfTest) {
                    if (lbl >= 0 && lbl < faultCounts.length) faultCounts[lbl]++;
                }

                double totalReactive = 0;
                double totalProactive = 0;
                StringBuilder analysis = new StringBuilder();
                analysis.append("Per-Fault Cost Analysis:\n");
                for (int c = 1; c <= 5; c++) {
                    long actual = faultCounts[c];
                    long tp = 0, fp = 0, fn = 0;
                    for (int i = 0; i < YClfTest.length; i++) {
                        if (YClfTest[i] == c && predictedLabels[i] == c) tp++;
                        else if (YClfTest[i] != c && predictedLabels[i] == c) fp++;
                        else if (YClfTest[i] == c && predictedLabels[i] != c) fn++;
                    }
                    FaultType ft = FaultType.fromValue(c);
                    double reactive = costModel.calculateReactiveCost(actual, ft);
                    double proactive = costModel.calculateProactiveCost(tp, fp, fn, ft);
                    totalReactive += reactive;
                    totalProactive += proactive;
                    analysis.append(String.format("  Fault %d (%s): actual=%d, TP=%d, FP=%d, FN=%d  Reactive=%.2f Proactive=%.2f%n",
                        c, ft, actual, tp, fp, fn, reactive, proactive));
                }
                System.out.println(analysis.toString());

                result.reactiveCost = totalReactive;
                result.proactiveCost = totalProactive;
                if (totalReactive > 0) {
                    result.savingPercentage = (totalReactive - totalProactive) / totalReactive * 100.0;
                }
                // Estimate extended costs (energy, operational) using test-set statistics
                try {
                    EnumMap<FaultType, Integer> proactiveCounts = new EnumMap<>(FaultType.class);
                    EnumMap<FaultType, Integer> reactiveCounts = new EnumMap<>(FaultType.class);
                    for (FaultType ft : FaultType.values()) {
                        proactiveCounts.put(ft, 0);
                        reactiveCounts.put(ft, 0);
                    }

                    int numPred = Math.min(predictedLabels.length, YClfTest.length);
                    for (int i = 0; i < numPred; i++) {
                        int p = predictedLabels[i];
                        int a = YClfTest[i];
                        if (p > 0 && p < 6) {
                            FaultType ft = FaultType.fromValue(p);
                            proactiveCounts.merge(ft, 1, Integer::sum);
                        }
                        if (a > 0 && a < 6) {
                            FaultType ft = FaultType.fromValue(a);
                            reactiveCounts.merge(ft, 1, Integer::sum);
                        }
                    }

                    double simDurationHours = (double) Math.max(1, numPred) * Configuration.monitoringTimeInterval / 3600.0;
                    double agentCpuTimeSec = numPred * 0.05; // estimate per-prediction agent CPU
                    double agentMemGbSec = numPred * 0.0001;
                    double agentBwGb = numPred * 0.00001;

                    double totalHostUtilSum = 0.0;
                    int utilSamples = 0;
                    for (int i = 0; i < Math.min(numPred, testFeatures.length); i++) {
                        double[] sample = testFeatures[i];
                        double sumVnfUtil = 0.0;
                        for (int v = 0; v < 3; v++) {
                            int base = v * 11;
                            double c0u = sample[base + 3];
                            double c0s = sample[base + 4];
                            double c1u = sample[base + 6];
                            double c1s = sample[base + 7];
                            double vnfUtil = (c0u + c0s + c1u + c1s) / 4.0;
                            sumVnfUtil += vnfUtil;
                        }
                        double hostUtil = sumVnfUtil / 3.0;
                        totalHostUtilSum += hostUtil;
                        utilSamples++;
                    }

                    double totalPeMips = 10000.0;
                    double totalRamGb = 1.0;

                    ExtendedCostModel extModel = new ExtendedCostModel(
                            simDurationHours,
                            agentCpuTimeSec,
                            agentMemGbSec,
                            agentBwGb,
                            proactiveCounts,
                            reactiveCounts,
                            new ArrayList<>(),
                            totalHostUtilSum,
                            utilSamples,
                            totalPeMips,
                            totalRamGb
                    );
                    ExtendedCostResult extResult = extModel.compute();
                    result.extendedCostResult = extResult;
                } catch (Exception ex) {
                    // best-effort: if anything fails, leave extendedCostResult null
                }

                results.add(result);
                ReportPrinter.printClassificationResult(result);
                ReportPrinter.printConfusionMatrix(result);
                System.out.printf("Resource Saving: %.2f%%  (Reactive=%.2f, Proactive=%.2f)%n",
                    result.savingPercentage, totalReactive, totalProactive);

                clfModel.close();
            }

            System.out.printf("%n=== Model Comparison Summary ===%n");
            System.out.printf("%-22s %8s %9s %9s %9s %9s %10s%n",
                "Model", "Acc", "Prec", "Recall", "F1", "Reactive", "Saving%");
            for (EvaluationResult r : results) {
                if (r == null || r.algorithm == null) {
                    continue;
                }
                System.out.printf("%-22s %8.4f %9.4f %9.4f %9.4f %10.2f %8.2f%%%n",
                    algoLabelOrName(r.algorithm),
                    r.accuracy, r.precision, r.recall, r.f1,
                    r.reactiveCost, r.savingPercentage);
            }

            try {
                ReportSaver.saveResults(results, "report/evaluation_results.txt");
                System.out.println("Saved evaluation results to report/evaluation_results.txt");
            } catch (IOException ioe) {
                System.err.println("Failed to save evaluation results: " + ioe.getMessage());
            }

        } catch (IOException e) {
            System.err.println("Failed to load dataset files: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Pipeline execution failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
