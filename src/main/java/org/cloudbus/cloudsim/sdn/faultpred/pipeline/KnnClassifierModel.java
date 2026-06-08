package org.cloudbus.cloudsim.sdn.faultpred.pipeline;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.ForkJoinPool;

public class KnnClassifierModel implements Serializable, AutoCloseable {

    private static final long serialVersionUID = 1L;

    private double[][] trainX;
    private int[] trainY;
    private int k;

    public KnnClassifierModel() {
        this(5);
    }

    public KnnClassifierModel(int k) {
        this.k = k;
    }

    public void train(double[][] X, int[] y) {
        this.trainX = X;
        this.trainY = y;
    }

    public int[] predict(double[][] X) {
        int[] preds = new int[X.length];
        int threads = Math.max(1, ModelConfig.getThreads());
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            pool.submit(() -> java.util.stream.IntStream.range(0, X.length).parallel().forEach(i -> preds[i] = predictOne(X[i]))).get();
        } catch (Exception e) {
            // fallback to sequential
            for (int i = 0; i < X.length; i++) preds[i] = predictOne(X[i]);
        } finally {
            pool.shutdown();
        }
        return preds;
    }

    private int predictOne(double[] x) {
        if (trainX == null || trainY == null || trainX.length == 0 || trainY.length == 0) {
            return 0;
        }
        int n = trainX.length;
        Integer[] idx = new Integer[n];
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) {
            dist[i] = squaredDistance(x, trainX[i]);
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return Double.compare(dist[a], dist[b]);
            }
        });
        double[] votes = new double[6];
        int actualK = Math.min(k, n);
        for (int r = 0; r < actualK; r++) {
            int label = trainY[idx[r]];
            if (label >= 0 && label < votes.length) {
                double weight = 1.0 / (1.0 + dist[idx[r]]);
                votes[label] += weight;
            }
        }
        int best = 0;
        for (int c = 1; c < votes.length; c++) {
            if (votes[c] > votes[best]) best = c;
        }
        return best;
    }

    private static double squaredDistance(double[] a, double[] b) {
        double sum = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return sum;
    }

    public void save(ObjectOutputStream out) throws java.io.IOException {
        out.writeObject(this);
    }

    public static KnnClassifierModel load(ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        return (KnnClassifierModel) in.readObject();
    }

    @Override
    public void close() throws Exception {
    }
}
