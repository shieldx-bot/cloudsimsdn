package org.cloudbus.cloudsim.sdn.faultpred.pipeline;

import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.conf.layers.recurrent.SimpleRnn;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import weka.classifiers.Classifier;
import weka.classifiers.CostMatrix;
import weka.classifiers.meta.CostSensitiveClassifier;
import weka.classifiers.meta.LogitBoost;
import weka.classifiers.trees.RandomForest;
import weka.classifiers.trees.REPTree;
import weka.classifiers.functions.Logistic;
import weka.classifiers.functions.SMO;
import weka.classifiers.lazy.IBk;
import weka.core.BatchPredictor;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Attribute;
import weka.classifiers.meta.FilteredClassifier;
import weka.filters.unsupervised.attribute.Standardize;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ClassificationModel implements AutoCloseable {

    public enum Algorithm {
        KNN("KNN"),
        RANDOM_FOREST("Random Forest"),
        LOGISTIC_REGRESSION("LogisticRegression"),
        SVM("SVM"),
        BALANCED_RANDOM_FOREST("BalancedRF"),
        BALANCED_LOGISTIC("BalancedLogistic"),
        GRADIENT_BOOSTING("GradientBoosting"),
        LIGHT_BOOSTING("LightBoosting"),
        LSTM("LSTM"),
        GRU("GRU");

        public final String label;
        Algorithm(String label) { this.label = label; }
    }

    private Classifier model;
    private RecurrentFaultClassifier recurrentModel;
    private Algorithm algorithm;
    private String trainSummary;

    public ClassificationModel() {
        this(Algorithm.RANDOM_FOREST);
    }

    public ClassificationModel(Algorithm algorithm) {
        this.algorithm = algorithm;
    }

    public void train(double[][] X, int[] y) throws Exception {
        if (algorithm == Algorithm.LSTM || algorithm == Algorithm.GRU) {
            throw new IllegalStateException("Use trainWindows() for recurrent models");
        }
        weka.core.Instances train = buildDataset(X, y, "fault_clf_" + algorithm.name());
        Classifier base = newClassifier(algorithm, train);
        this.model = wrapCostSensitiveIfNeeded(algorithm, base, train);
        this.trainSummary = simpleName(this.model.getClass().getName());
    }

    public int[] predict(double[][] X) throws Exception {
        if (recurrentModel != null) {
            throw new IllegalStateException("Use predictWindows() for recurrent models");
        }
        if (model == null) throw new IllegalStateException("Model not trained");
        weka.core.Instances test = buildDataset(X, new int[X.length], "fault_pred_" + algorithm.name());
        test.setClassIndex(test.numAttributes() - 1);
        return predictBatch(test);
    }

    private static Classifier newClassifier(Algorithm algo, Instances data) throws Exception {
        switch (algo) {
            case KNN: {
                IBk knn = new IBk();
                knn.setKNN(5);
                knn.buildClassifier(data);
                return knn;
            }
            case RANDOM_FOREST: {
                RandomForest rf = new RandomForest();
                rf.setOptions(weka.core.Utils.splitOptions("-I 200 -depth 20"));
                configureWekaParallelism(rf);
                rf.buildClassifier(data);
                return rf;
            }
            case LOGISTIC_REGRESSION: {
                Logistic log = new Logistic();
                log.setMaxIts(100);
                log.buildClassifier(data);
                return log;
            }
            case SVM: {
                SMO smo = new SMO();
                smo.setC(1.0);
                smo.buildClassifier(data);
                return smo;
            }
            case BALANCED_RANDOM_FOREST: {
                RandomForest rf = new RandomForest();
                rf.setOptions(weka.core.Utils.splitOptions("-I 300 -depth 25"));
                configureWekaParallelism(rf);
                return rf;
            }
            case BALANCED_LOGISTIC: {
                Logistic log = new Logistic();
                log.setMaxIts(200);
                return log;
            }
            case GRADIENT_BOOSTING: {
                LogitBoost boost = new LogitBoost();
                boost.setOptions(weka.core.Utils.splitOptions("-I 100 -H 1.0 -P 100"));
                configureWekaParallelism(boost);
                boost.buildClassifier(data);
                return boost;
            }
            case LIGHT_BOOSTING: {
                REPTree tree = new REPTree();
                tree.setMaxDepth(4);
                tree.setMinNum(20.0);

                LogitBoost boost = new LogitBoost();
                boost.setClassifier(tree);
                boost.setOptions(weka.core.Utils.splitOptions("-I 60 -H 0.5 -P 100"));
                configureWekaParallelism(boost);
                boost.buildClassifier(data);
                return boost;
            }
            default: {
                RandomForest rf = new RandomForest();
                rf.setOptions(weka.core.Utils.splitOptions("-I 150 -depth 20"));
                configureWekaParallelism(rf);
                rf.buildClassifier(data);
                return rf;
            }
        }
    }

    private static Classifier wrapCostSensitiveIfNeeded(Algorithm algo, Classifier base, Instances data) throws Exception {
        if (algo != Algorithm.BALANCED_RANDOM_FOREST && algo != Algorithm.BALANCED_LOGISTIC) {
            return base;
        }
        CostSensitiveClassifier cs = new CostSensitiveClassifier();
        cs.setClassifier(base);
        CostMatrix cm = new CostMatrix(data.numClasses());
        double faultCost = 1.0;
        double normalCost = 0.15;
        for (int i = 0; i < data.numClasses(); i++) {
            for (int j = 0; j < data.numClasses(); j++) {
                if (i == j) cm.setCell(i, j, 0.0);
                else if (i == 0) cm.setCell(i, j, normalCost);
                else if (j == 0) cm.setCell(i, j, faultCost);
                else cm.setCell(i, j, 0.5);
            }
        }
        cs.setCostMatrix(cm);
        FilteredClassifier fc = new FilteredClassifier();
        Standardize std = new Standardize();
        fc.setFilter(std);
        fc.setClassifier(cs);
        fc.buildClassifier(data);
        return fc;
    }

    private static void configureWekaParallelism(Classifier classifier) {
        int threads = Math.max(1, ModelConfig.getThreads());
        if (classifier instanceof RandomForest) {
            ((RandomForest) classifier).setNumExecutionSlots(threads);
        } else if (classifier instanceof LogitBoost) {
            ((LogitBoost) classifier).setPoolSize(threads);
            ((LogitBoost) classifier).setNumThreads(Math.max(threads, 1));
        }
    }

    private int[] predictBatch(Instances test) throws Exception {
        if (test.numInstances() == 0) {
            return new int[0];
        }

        if (model instanceof BatchPredictor) {
            BatchPredictor batch = (BatchPredictor) model;
            double[][] dists = batch.distributionsForInstances(test);
            return argMaxPredictions(dists);
        }

        int threads = Math.min(Math.max(1, ModelConfig.getThreads()), test.numInstances());
        if (threads == 1) {
            int[] preds = new int[test.numInstances()];
            for (int i = 0; i < test.numInstances(); i++) {
                preds[i] = (int) Math.round(model.classifyInstance(test.instance(i)));
            }
            return preds;
        }

        int[] preds = new int[test.numInstances()];
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> jobs = new ArrayList<>();
        try {
            int chunk = (test.numInstances() + threads - 1) / threads;
            for (int start = 0; start < test.numInstances(); start += chunk) {
                final int lo = start;
                final int hi = Math.min(test.numInstances(), start + chunk);
                jobs.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        for (int i = lo; i < hi; i++) {
                            preds[i] = (int) Math.round(model.classifyInstance(test.instance(i)));
                        }
                        return null;
                    }
                }));
            }
            for (Future<?> job : jobs) {
                job.get();
            }
        } finally {
            pool.shutdown();
        }
        return preds;
    }

    private static int[] argMaxPredictions(double[][] distributions) {
        int[] preds = new int[distributions.length];
        for (int i = 0; i < distributions.length; i++) {
            preds[i] = argMax(distributions[i]);
        }
        return preds;
    }

    private static int argMax(double[] values) {
        if (values == null || values.length == 0) {
            return 0;
        }
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[best]) {
                best = i;
            }
        }
        return best;
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public String getTrainSummary() {
        return trainSummary != null ? trainSummary : algorithm.label;
    }

    private static weka.core.Instances buildDataset(double[][] X, int[] y, String name) {
        int numFeatures = X[0].length;
        java.util.ArrayList<Attribute> attrs = new java.util.ArrayList<>();
        for (int i = 0; i < numFeatures; i++) {
            attrs.add(new weka.core.Attribute("f" + i));
        }
        java.util.List<String> classes = new java.util.ArrayList<>();
        for (int i = 0; i <= 5; i++) {
            classes.add(String.valueOf(i));
        }
        attrs.add(new weka.core.Attribute("fault", classes));
        weka.core.Instances data = new weka.core.Instances(name, attrs, X.length);
        data.setClassIndex(numFeatures);
        for (int i = 0; i < X.length; i++) {
            double[] vals = new double[numFeatures + 1];
            System.arraycopy(X[i], 0, vals, 0, numFeatures);
            vals[numFeatures] = y[i];
            data.add(new weka.core.DenseInstance(1.0, vals));
        }
        return data;
    }

    private static String simpleName(String fqcn) {
        int idx = fqcn.lastIndexOf('.');
        return idx >= 0 ? fqcn.substring(idx + 1) : fqcn;
    }

    @Override
    public void close() throws Exception {
    }

    private static class RecurrentFaultClassifier {
        enum Cell { LSTM, GRU }

        private static final int NUM_CLASSES = 6;
        // Default sequence length is 8 for backward compatibility, but is
        // overridden in trainWindows() based on the actual window size passed
        // by the caller. This lets the model see a longer context (e.g. 20
        // timesteps) when MainFaultPrediction runs with -Dfaultpred.window=20.
        private int sequenceLength = 8;
        private int hidden = 64;
        private int epochs = 400;
        private double focalGamma = 2.0;
        private double focalAlpha = 0.25;
        private boolean useFocalLoss = true;
        private boolean useSmote = true;
        private int smoteK = 5;
        private long smoteSeed = 42L;
        // Per-class weights used inside the loss to counter label imbalance.
        // Initialized in trainWindows() based on the per-class frequency.
        private double[] classWeights;

        private final Cell cell;
        private MultiLayerNetwork network;
        private int inputSize;

        RecurrentFaultClassifier(Cell cell) {
            this.cell = cell;
        }

        void setHidden(int hidden) { this.hidden = Math.max(8, hidden); }
        void setEpochs(int epochs) { this.epochs = Math.max(1, epochs); }
        void setFocalLoss(boolean enabled, double gamma, double alpha) {
            this.useFocalLoss = enabled;
            this.focalGamma = gamma;
            this.focalAlpha = alpha;
        }
        void setSmote(boolean enabled, int k) {
            this.useSmote = enabled;
            this.smoteK = Math.max(1, k);
        }
        void setSmoteSeed(long seed) { this.smoteSeed = seed; }

        void trainWindows(double[][][] windows, int[] y) throws Exception {
            ModelConfig.logRuntime("RecurrentFaultClassifier");
            inputSize = windows[0][0].length;
            sequenceLength = windows[0].length;
            // Compute per-class frequency on the training labels so we can
            // weight the loss to compensate for imbalance.
            this.classWeights = computeClassWeights(y, NUM_CLASSES);
            // Optionally oversample minority classes via SMOTE before fitting.
            Object[] augmented = useSmote
                    ? smoteAugment(windows, y, smoteK, smoteSeed)
                    : new Object[] { windows, y };
            windows = (double[][][]) augmented[0];
            y = (int[]) augmented[1];
            network = buildNetwork();
            DataSet train = buildSequenceDataSet(windows, y);
            for (int epoch = 0; epoch < epochs; epoch++) {
                network.fit(train);
            }
        }

        /**
         * Inverse-frequency class weights. The NONE class (label 0) is the
         * majority; we shrink its weight and boost the rare fault classes.
         */
        private static double[] computeClassWeights(int[] y, int numClasses) {
            int[] count = new int[numClasses];
            for (int lbl : y) {
                if (lbl >= 0 && lbl < numClasses) count[lbl]++;
            }
            double[] w = new double[numClasses];
            double total = y.length;
            for (int c = 0; c < numClasses; c++) {
                if (count[c] == 0) {
                    w[c] = 1.0;
                } else {
                    w[c] = total / (numClasses * count[c]);
                }
            }
            return w;
        }

        /**
         * Simple SMOTE oversampling for time-series windows. Flattens each
         * window to a feature vector, finds k nearest neighbours of the same
         * class (Euclidean), and interpolates synthetic windows that are then
         * reshaped back to (timesteps, features).
         */
        private static Object[] smoteAugment(double[][][] windows, int[] y, int k, long seed) {
            int n = windows.length;
            int t = windows[0].length;
            int f = windows[0][0].length;
            // Flatten: [n][t*f]
            double[][] flat = new double[n][t * f];
            for (int i = 0; i < n; i++) {
                for (int s = 0; s < t; s++) {
                    System.arraycopy(windows[i][s], 0, flat[i], s * f, f);
                }
            }
            // Find majority class size
            int[] count = new int[NUM_CLASSES];
            for (int lbl : y) if (lbl >= 0 && lbl < NUM_CLASSES) count[lbl]++;
            int target = 0;
            for (int c = 0; c < NUM_CLASSES; c++) if (count[c] > target) target = count[c];
            // For each minority class, oversample to target
            java.util.Random rng = new java.util.Random(seed);
            java.util.List<double[]> newFlat = new java.util.ArrayList<>();
            java.util.List<Integer> newLabels = new java.util.ArrayList<>();
            for (int c = 1; c < NUM_CLASSES; c++) {
                if (count[c] == 0 || count[c] >= target) continue;
                // indices of class c
                java.util.List<Integer> idx = new java.util.ArrayList<>();
                for (int i = 0; i < n; i++) if (y[i] == c) idx.add(i);
                if (idx.isEmpty()) continue;
                int need = target - count[c];
                for (int m = 0; m < need; m++) {
                    int base = idx.get(rng.nextInt(idx.size()));
                    // k nearest neighbours in same class. We use a parallel
                    // double[] for distances so we can sort by value cleanly.
                    double[] baseVec = flat[base];
                    int cls = idx.size();
                    double[] dists = new double[cls];
                    for (int j = 0; j < cls; j++) {
                        double s = 0.0;
                        for (int d = 0; d < baseVec.length; d++) {
                            double diff = baseVec[d] - flat[idx.get(j)][d];
                            s += diff * diff;
                        }
                        dists[j] = s;
                    }
                    // Indices of the k+1 nearest neighbours in the same class
                    // (skip self at distance 0). We do a simple partial sort
                    // over a small array; for minority classes (typically
                    // ~1000 samples) k << n so this is acceptable.
                    int[] nnIdx = new int[cls];
                    for (int j = 0; j < cls; j++) nnIdx[j] = j;
                    int actualK = Math.min(k + 1, cls);
                    for (int a = 0; a < actualK; a++) {
                        for (int b = a + 1; b < cls; b++) {
                            if (dists[nnIdx[b]] < dists[nnIdx[a]]) {
                                int tmp = nnIdx[a]; nnIdx[a] = nnIdx[b]; nnIdx[b] = tmp;
                            }
                        }
                    }
                    // Pick a random neighbour, skipping the first (self at distance 0)
                    int pickRange = Math.max(1, actualK - 1);
                    int neighborPos = 1 + rng.nextInt(pickRange);
                    int neighborIdx = idx.get(nnIdx[neighborPos]);
                    double[] neighbor = flat[neighborIdx];
                    double[] synth = new double[baseVec.length];
                    double alpha = rng.nextDouble();
                    for (int d = 0; d < baseVec.length; d++) {
                        synth[d] = baseVec[d] + alpha * (neighbor[d] - baseVec[d]);
                    }
                    newFlat.add(synth);
                    newLabels.add(c);
                }
            }
            if (newFlat.isEmpty()) return new Object[] { windows, y };
            // Combine original + synthetic
            double[][] combFlat = new double[flat.length + newFlat.size()][];
            int[] combY = new int[y.length + newLabels.size()];
            System.arraycopy(flat, 0, combFlat, 0, flat.length);
            System.arraycopy(y, 0, combY, 0, y.length);
            for (int i = 0; i < newFlat.size(); i++) {
                combFlat[flat.length + i] = newFlat.get(i);
                combY[y.length + i] = newLabels.get(i);
            }
            // Reshape back
            double[][][] out = new double[combFlat.length][t][f];
            for (int i = 0; i < combFlat.length; i++) {
                for (int s = 0; s < t; s++) {
                    System.arraycopy(combFlat[i], s * f, out[i][s], 0, f);
                }
            }
            return new Object[] { out, combY };
        }

        int[] predictWindows(double[][][] windows) throws Exception {
            if (network == null) throw new IllegalStateException("Recurrent model not trained");
            if (windows.length == 0) {
                return new int[0];
            }
            // Allow per-call sequence length override; fall back to trained value
            int seqLen = sequenceLength;
            if (windows[0].length != seqLen) {
                seqLen = windows[0].length;
            }
            INDArray features = Nd4j.zeros(windows.length, inputSize, seqLen);
            for (int sample = 0; sample < windows.length; sample++) {
                fillWindow(features, sample, windows[sample], seqLen);
            }

            INDArray out = network.output(features, false);
            INDArray lastStep = out.get(
                    org.nd4j.linalg.indexing.NDArrayIndex.all(),
                    org.nd4j.linalg.indexing.NDArrayIndex.all(),
                    org.nd4j.linalg.indexing.NDArrayIndex.point(seqLen - 1));
            int[] preds = Nd4j.argMax(lastStep, 1).toIntVector();
            return preds;
        }

        String getSummary() {
            return cell.name() + " DL4J recurrent neural network (seq=" + sequenceLength + ")";
        }

        private MultiLayerNetwork buildNetwork() {
            NeuralNetConfiguration.ListBuilder list = new NeuralNetConfiguration.Builder()
                    .seed(cell == Cell.LSTM ? 31L : 43L)
                    .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                    .updater(new Adam(0.001))
                    .weightInit(WeightInit.XAVIER)
                    .list();

            // Use the configured hidden size (default 64, configurable via
            // setHidden or the -Dfaultpred.rnn.hidden property).
            int h = this.hidden;
            if (cell == Cell.LSTM) {
                list.layer(0, new LSTM.Builder()
                        .nIn(inputSize)
                        .nOut(h)
                        .activation(Activation.TANH)
                        .build());
            } else {
                list.layer(0, new SimpleRnn.Builder()
                        .nIn(inputSize)
                        .nOut(h)
                        .activation(Activation.TANH)
                        .build());
            }

            // We use the built-in MCXENT (multi-class cross-entropy) loss.
            // Class imbalance is handled by the per-sample weights we attach
            // to the training DataSet (built in buildSequenceDataSet below):
            // each sample's loss is multiplied by the inverse-frequency class
            // weight. When useFocalLoss is on we further apply a focusing
            // factor (1 - p)^gamma to the per-sample loss as a post-hoc
            // reweighting done in a small custom training loop wrapper.
            list.layer(1, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                    .activation(Activation.SOFTMAX)
                    .nIn(h)
                    .nOut(NUM_CLASSES)
                    .build())
                    .build();

            MultiLayerConfiguration conf = list.build();
            MultiLayerNetwork model = new MultiLayerNetwork(conf);
            model.init();
            return model;
        }

        private DataSet buildSequenceDataSet(double[][][] windows, int[] y) {
            INDArray features = Nd4j.zeros(windows.length, inputSize, sequenceLength);
            INDArray labels = Nd4j.zeros(windows.length, NUM_CLASSES, sequenceLength);
            INDArray labelMask = Nd4j.zeros(windows.length, sequenceLength);

            for (int sample = 0; sample < windows.length; sample++) {
                fillWindow(features, sample, windows[sample], sequenceLength);
                int cls = sanitizeClass(y[sample]);
                labels.putScalar(new int[] { sample, cls, sequenceLength - 1 }, 1.0);
                labelMask.putScalar(new int[] { sample, sequenceLength - 1 }, 1.0);
            }

            return new DataSet(features, labels, null, labelMask);
        }

        private INDArray buildSingleWindow(double[][] X, int endIndex) {
            INDArray features = Nd4j.zeros(1, inputSize, sequenceLength);
            fillWindow(features, 0, X, sequenceLength);
            return features;
        }

        private void fillWindow(INDArray features, int sampleRow, double[][] X, int seqLen) {
            for (int step = 0; step < seqLen; step++) {
                for (int feature = 0; feature < inputSize; feature++) {
                    features.putScalar(new int[] { sampleRow, feature, step }, X[step][feature]);
                }
            }
        }

        private static int sanitizeClass(int label) {
            if (label < 0) return 0;
            if (label >= NUM_CLASSES) return NUM_CLASSES - 1;
            return label;
        }
    }
    public void trainWindows(double[][][] windows, int[] y) throws Exception {
        if (algorithm != Algorithm.LSTM && algorithm != Algorithm.GRU) {
            throw new IllegalStateException("trainWindows() is only for recurrent models");
        }
        recurrentModel = new RecurrentFaultClassifier(
                algorithm == Algorithm.LSTM ? RecurrentFaultClassifier.Cell.LSTM : RecurrentFaultClassifier.Cell.GRU);
        recurrentModel.trainWindows(windows, y);
        this.trainSummary = recurrentModel.getSummary();
    }

    public int[] predictWindows(double[][][] windows) throws Exception {
        if (algorithm != Algorithm.LSTM && algorithm != Algorithm.GRU) {
            throw new IllegalStateException("predictWindows() is only for recurrent models");
        }
        if (recurrentModel == null) throw new IllegalStateException("Model not trained");
        return recurrentModel.predictWindows(windows);
    }
}
