package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

public class ReportPrinter {

    private static final FaultType[] FAULT_VALUES = {
        FaultType.NONE,
        FaultType.BRIDGE_BYTE_STREAK,
        FaultType.INTERFACE_DOWN,
        FaultType.INTERFACE_LOSS_START,
        FaultType.MEMORY_STRESS_START,
        FaultType.VCPU_OVERLOAD_START
    };

    public static void printCostModel(FaultCostModel model) {
        System.out.println("=== Fault Cost Model ===");
        System.out.println(FaultCostModel.formatCostTable());
        System.out.println();
    }

    public static void printRegressionMetrics(double mae, double rmse) {
        System.out.println("=== Regression Metrics ===");
        System.out.printf("MAE  : %.6f%n", mae);
        System.out.printf("RMSE : %.6f%n", rmse);
        System.out.println();
    }

    public static void printClassificationResult(EvaluationResult res) {
        System.out.println("=== Classification Results (Test Set) ===");
        System.out.printf("Accuracy  : %.4f%n", res.accuracy);
        System.out.printf("Precision : %.4f%n", res.precision);
        System.out.printf("Recall    : %.4f%n", res.recall);
        System.out.printf("F1-Score  : %.4f%n", res.f1);
        System.out.println();

        if (res.extendedCostResult != null) {
            System.out.println("=== Extended Cost Summary ===");
            System.out.printf("Reactive Total: %.2f, Proactive Total: %.2f, Saving: %.2f%%%n",
                res.extendedCostResult.reactiveTotal,
                res.extendedCostResult.proactiveTotal,
                res.extendedCostResult.savingPercentage);
            System.out.printf("Energy (Reactive): %.2f, Energy (Proactive): %.2f%n",
                res.extendedCostResult.energyCostReactive,
                res.extendedCostResult.energyCostProactive);
            System.out.printf("Operational Cost: %.2f, Deployment Amortised: %.2f%n",
                res.extendedCostResult.operationalCost,
                res.extendedCostResult.amortisedDeploymentCost);
            System.out.println();
        }

        System.out.println("Per-Class Metrics:");
        System.out.printf("%-25s %8s %8s %8s %8s %n",
            "FaultType", "Count", "Prec", "Rec", "F1");
        for (int c = 0; c < FAULT_VALUES.length; c++) {
            System.out.printf("%-25s %8d %8.4f %8.4f %8.4f %n",
                FAULT_VALUES[c], res.actualCounts[c],
                res.perClassPrecision[c] == 0 ? 0 : res.perClassPrecision[c],
                res.perClassRecall[c] == 0 ? 0 : res.perClassRecall[c],
                res.perClassF1[c] == 0 ? 0 : res.perClassF1[c]);
        }
        System.out.println();
    }

    public static void printConfusionMatrix(EvaluationResult res) {
        System.out.println("=== Confusion Matrix (rows=actual, cols=predicted) ===");
        System.out.printf("%-25s", "");
        for (int j = 0; j < FAULT_VALUES.length; j++) {
            System.out.printf("%5d ", FAULT_VALUES[j].getValue());
        }
        System.out.println();
        for (int i = 0; i < FAULT_VALUES.length; i++) {
            System.out.printf("%-25s", FAULT_VALUES[i].name());
            for (int j = 0; j < FAULT_VALUES.length; j++) {
                System.out.printf("%5d ", res.confusion[i][j]);
            }
            System.out.println();
        }
        System.out.println();
    }

    public static void printResourceSaving(EvaluationResult res, FaultCostModel costModel) {
        System.out.println("=== Resource Saving Assessment ===");
        System.out.printf("Reactive Total Cost   : %.2f%n", res.reactiveCost);
        System.out.printf("Proactive Total Cost  : %.2f%n", res.proactiveCost);
        System.out.printf("Resource Saving (%% )  : %.2f%%%n", res.savingPercentage);
        System.out.println();
    }
}
