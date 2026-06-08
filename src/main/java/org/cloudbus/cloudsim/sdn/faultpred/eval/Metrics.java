package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

public class Metrics {

    private static final FaultType[] FAULT_VALUES = {
        FaultType.NONE,
        FaultType.BRIDGE_BYTE_STREAK,
        FaultType.INTERFACE_DOWN,
        FaultType.INTERFACE_LOSS_START,
        FaultType.MEMORY_STRESS_START,
        FaultType.VCPU_OVERLOAD_START
    };

    public static EvaluationResult computeClassificationMetrics(int[] actual, int[] predicted) {
        EvaluationResult res = new EvaluationResult();
        int numClasses = FAULT_VALUES.length;
        int len = Math.min(actual.length, predicted.length);
        for (int i = 0; i < len; i++) {
            int a = actual[i];
            int p = predicted[i];
            if (a < 0 || a >= numClasses || p < 0 || p >= numClasses) {
                continue;
            }
            res.actualCounts[a]++;
            res.predCounts[p]++;
            res.confusion[a][p]++;
            if (a == p && a != 0) res.tp++;
            else if (p != 0 && a != p) res.fp++;
            else if (a != 0 && p == 0) res.fn++;
            else res.tn++;
        }

        res.accuracy = (double) (res.tp + res.tn) / actual.length;
        res.precision = (res.tp + res.fp) == 0 ? 0 : (double) res.tp / (res.tp + res.fp);
        res.recall = (res.tp + res.fn) == 0 ? 0 : (double) res.tp / (res.tp + res.fn);
        res.f1 = (res.precision + res.recall) == 0 ? 0 : 2.0 * res.precision * res.recall / (res.precision + res.recall);

        for (int c = 0; c < numClasses; c++) {
            if (res.confusion[c][c] == 0 && sumRow(res.confusion[c]) == 0 && sumCol(res.confusion, c) == 0) continue;
            double tp_c = res.confusion[c][c];
            double fp_c = sumCol(res.confusion, c) - tp_c;
            double fn_c = sumRow(res.confusion[c]) - tp_c;
            double prec = (tp_c + fp_c) == 0 ? 0 : tp_c / (tp_c + fp_c);
            double rec = (tp_c + fn_c) == 0 ? 0 : tp_c / (tp_c + fn_c);
            double f1 = (prec + rec) == 0 ? 0 : 2 * prec * rec / (prec + rec);
            res.perClassPrecision[c] = prec;
            res.perClassRecall[c] = rec;
            res.perClassF1[c] = f1;
        }
        return res;
    }

    public static double computeMAE(double[][] predicted, double[][] actual) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < predicted.length; i++) {
            for (int j = 0; j < predicted[i].length; j++) {
                sum += Math.abs(predicted[i][j] - actual[i][j]);
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    public static double computeRMSE(double[][] predicted, double[][] actual) {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < predicted.length; i++) {
            for (int j = 0; j < predicted[i].length; j++) {
                double err = predicted[i][j] - actual[i][j];
                sum += err * err;
                count++;
            }
        }
        return count == 0 ? 0 : Math.sqrt(sum / count);
    }

    public static int sumRow(int[] row) {
        int s = 0;
        for (int v : row) s += v;
        return s;
    }

    public static int sumCol(int[][] mat, int col) {
        int s = 0;
        for (int[] row : mat) {
            if (col < row.length) s += row[col];
        }
        return s;
    }
}
