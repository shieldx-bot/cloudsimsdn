package org.cloudbus.cloudsim.sdn.faultpred.pipeline;

import weka.classifiers.functions.SMO;
import weka.classifiers.Classifier;
import weka.core.DenseInstance;
import weka.core.Instances;
import weka.core.Attribute;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DtreeClassifierModel implements AutoCloseable {

    private Classifier model;

    public DtreeClassifierModel() {
    }

    private DtreeClassifierModel(Classifier model) {
        this.model = model;
    }

    public void train(double[][] X, int[] y) throws Exception {
        Instances train = buildDataset(X, y, "fault_smo");
        SMO smo = new SMO();
        smo.buildClassifier(train);
        model = smo;
    }

    public int[] predict(double[][] X) throws Exception {
        Instances test = buildDataset(X, new int[X.length], "fault_pred");
        test.setClassIndex(test.numAttributes() - 1);
        int threads = Math.min(Math.max(1, ModelConfig.getThreads()), Math.max(1, test.numInstances()));
        if (threads == 1) {
            int[] preds = new int[X.length];
            for (int i = 0; i < test.numInstances(); i++) {
                preds[i] = (int) Math.round(model.classifyInstance(test.instance(i)));
            }
            return preds;
        }

        int[] preds = new int[X.length];
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

    private static Instances buildDataset(double[][] X, int[] y, String name) {
        int numFeatures = X[0].length;
        ArrayList<Attribute> attrs = new ArrayList<>();
        for (int i = 0; i < numFeatures; i++) {
            attrs.add(new Attribute("f" + i));
        }
        ArrayList<String> classes = new ArrayList<>();
        for (int i = 0; i <= 5; i++) {
            classes.add(String.valueOf(i));
        }
        attrs.add(new Attribute("fault", classes));
        Instances data = new Instances(name, attrs, X.length);
        data.setClassIndex(numFeatures);
        for (int i = 0; i < X.length; i++) {
            double[] vals = new double[numFeatures + 1];
            System.arraycopy(X[i], 0, vals, 0, numFeatures);
            vals[numFeatures] = y[i];
            data.add(new DenseInstance(1.0, vals));
        }
        return data;
    }

    @Override
    public void close() throws Exception {
    }
}
