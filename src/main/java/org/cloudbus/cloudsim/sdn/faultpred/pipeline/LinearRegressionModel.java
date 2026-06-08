package org.cloudbus.cloudsim.sdn.faultpred.pipeline;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

public class LinearRegressionModel implements Serializable, AutoCloseable {

    private static final long serialVersionUID = 1L;

    private double[][] coefficients;
    private int numTargets;

    public LinearRegressionModel() {
        this.coefficients = null;
        this.numTargets = 0;
    }

    private LinearRegressionModel(double[][] coefficients, int numTargets) {
        this.coefficients = coefficients;
        this.numTargets = numTargets;
    }

    public void train(double[][] X, double[][] y) throws Exception {
        int nSamples = X.length;
        if (nSamples == 0) {
            coefficients = new double[0][0];
            numTargets = 0;
            return;
        }
        int nFeatures = X[0].length;
        int nTargets = y[0].length;
        int p = nFeatures + 1;
        coefficients = new double[nTargets][p];
        numTargets = nTargets;

        final int threads = Math.max(1, ModelConfig.getThreads());
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        List<Future<double[]>> futures = new ArrayList<>();

        for (int t = 0; t < nTargets; t++) {
            final int targetIdx = t;
            futures.add(ex.submit(new Callable<double[]>() {
                @Override
                public double[] call() throws Exception {
                    // Default to commons-math. ND4J is opt-in because mixed backend types can fail on CPU installs.
                    if (shouldUseNd4j()) {
                        try {
                            return trainWithNd4j(X, y, targetIdx);
                        } catch (Throwable ndEx) {
                            // fall through to commons-math fallback
                        }
                    }
                    double[] yt = new double[y.length];
                    for (int i = 0; i < y.length; i++) yt[i] = y[i][targetIdx];
                    OLSMultipleLinearRegression lr = new OLSMultipleLinearRegression();
                    lr.newSampleData(yt, X);
                    return lr.estimateRegressionParameters();
                }
            }));
        }

        for (int t = 0; t < nTargets; t++) {
            coefficients[t] = futures.get(t).get();
        }
        ex.shutdown();
    }

    private static boolean isNd4jAvailable() {
        try {
            Class.forName("org.nd4j.linalg.factory.Nd4j");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean shouldUseNd4j() {
        if (!isNd4jAvailable()) {
            return false;
        }
        String value = System.getProperty("faultpred.useNd4j");
        if (value == null || value.isEmpty()) {
            value = System.getenv("FAULTPRED_USE_ND4J");
        }
        if (value == null || value.isEmpty()) {
            return false;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static double[] trainWithNd4j(double[][] X, double[][] y, int targetIdx) {
        // Compute beta = (Xb^T Xb)^-1 Xb^T y using ND4J (will use GPU backend if ND4J is configured that way)
        org.nd4j.linalg.api.ndarray.INDArray Xarr = org.nd4j.linalg.factory.Nd4j.create(X);
        org.nd4j.linalg.api.ndarray.INDArray ones = org.nd4j.linalg.factory.Nd4j.ones(Xarr.rows(), 1);
        org.nd4j.linalg.api.ndarray.INDArray Xb = org.nd4j.linalg.factory.Nd4j.hstack(ones, Xarr);
        double[] yt = new double[y.length];
        for (int i = 0; i < y.length; i++) yt[i] = y[i][targetIdx];
        org.nd4j.linalg.api.ndarray.INDArray ycol = org.nd4j.linalg.factory.Nd4j.create(yt, new int[] {yt.length, 1});
        org.nd4j.linalg.api.ndarray.INDArray Xt = Xb.transpose();
        org.nd4j.linalg.api.ndarray.INDArray XtX = Xt.mmul(Xb);
        org.nd4j.linalg.api.ndarray.INDArray XtXinv = org.nd4j.linalg.inverse.InvertMatrix.invert(XtX, false);
        org.nd4j.linalg.api.ndarray.INDArray betas = XtXinv.mmul(Xt).mmul(ycol);
        double[] flat = betas.data().asDouble();
        if (flat.length == Xb.columns()) return flat;
        double[] res = new double[Xb.columns()];
        System.arraycopy(flat, 0, res, 0, Math.min(flat.length, res.length));
        return res;
    }

    public double[][] predict(double[][] X) throws Exception {
        if (coefficients == null) return new double[0][0];
        int samples = X.length;
        int nTargets = coefficients.length;
        if (samples == 0) return new double[0][nTargets];
        int nFeatures = X[0].length;
        double[][] preds = new double[samples][nTargets];

        // ND4J remains opt-in; Commons Math is the stable default path.
        if (shouldUseNd4j()) {
            try {
                org.nd4j.linalg.api.ndarray.INDArray Xarr = org.nd4j.linalg.factory.Nd4j.create(X);
                org.nd4j.linalg.api.ndarray.INDArray ones = org.nd4j.linalg.factory.Nd4j.ones(Xarr.rows(), 1);
                org.nd4j.linalg.api.ndarray.INDArray Xb = org.nd4j.linalg.factory.Nd4j.hstack(ones, Xarr);
                double[][] betaMat = new double[nFeatures + 1][nTargets];
                for (int t = 0; t < nTargets; t++) {
                    for (int j = 0; j < coefficients[t].length; j++) {
                        betaMat[j][t] = coefficients[t][j];
                    }
                }
                org.nd4j.linalg.api.ndarray.INDArray betas = org.nd4j.linalg.factory.Nd4j.create(betaMat);
                org.nd4j.linalg.api.ndarray.INDArray res = Xb.mmul(betas);
                double[] flat = res.data().asDouble();
                for (int i = 0; i < samples; i++) {
                    for (int t = 0; t < nTargets; t++) {
                        preds[i][t] = flat[i * nTargets + t];
                    }
                }
                return preds;
            } catch (Throwable e) {
                // fallback to threaded CPU compute below
            }
        }

        final int threads = Math.max(1, ModelConfig.getThreads());
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(threads, Math.max(1, samples)));
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < samples; i++) {
            final int idx = i;
            futures.add(ex.submit(new Callable<Object>() {
                @Override
                public Object call() {
                    for (int t = 0; t < nTargets; t++) {
                        double[] beta = coefficients[t];
                        double s = beta[0];
                        for (int j = 0; j < nFeatures; j++) {
                            s += X[idx][j] * beta[j + 1];
                        }
                        preds[idx][t] = s;
                    }
                    return null;
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        ex.shutdown();
        return preds;
    }

    @Override
    public void close() throws Exception {
    }
}
