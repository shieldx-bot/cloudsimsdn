package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.ClassificationModel;

public class EvaluationResult {
    private static final int NUM_CLASSES = 6;

    public String modelName;
    public ClassificationModel.Algorithm algorithm;

    public int tp, fp, tn, fn;
    public double accuracy, precision, recall, f1;

    public int[] predCounts = new int[NUM_CLASSES];
    public int[] actualCounts = new int[NUM_CLASSES];
    public int[][] confusion = new int[NUM_CLASSES][NUM_CLASSES];

    public double[] perClassPrecision = new double[NUM_CLASSES];
    public double[] perClassRecall = new double[NUM_CLASSES];
    public double[] perClassF1 = new double[NUM_CLASSES];

    public double regressionMAE;
    public double regressionRMSE;

    public double proactiveCost;
    public double reactiveCost;
    public double savingPercentage;
    public ExtendedCostResult extendedCostResult;

    public EvaluationResult cloneEmpty(String modelName, ClassificationModel.Algorithm algorithm) {
        EvaluationResult r = new EvaluationResult();
        r.modelName = modelName;
        r.algorithm = algorithm;
        return r;
    }
}
