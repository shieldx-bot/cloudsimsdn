package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReportSaver {

    public static void saveResults(List<EvaluationResult> results, String filePath) throws IOException {
        Path p = Paths.get(filePath);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        try (BufferedWriter bw = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            bw.write("Model\tAlgorithm\tAccuracy\tPrecision\tRecall\tF1\tReactiveCost\tProactiveCost\tSaving%\tMAE\tRMSE");
            bw.newLine();
            for (EvaluationResult r : results) {
                String modelLabel = r.modelName != null ? r.modelName : (r.algorithm != null ? r.algorithm.label : "unknown");
                String algoLabel = r.algorithm != null ? r.algorithm.label : "unknown";
                bw.write(String.format(Locale.US, "%s\t%s\t%.6f\t%.6f\t%.6f\t%.6f\t%.2f\t%.2f\t%.2f\t%.6f\t%.6f",
                        modelLabel,
                        algoLabel,
                        r.accuracy, r.precision, r.recall, r.f1,
                        r.reactiveCost, r.proactiveCost, r.savingPercentage,
                        r.regressionMAE, r.regressionRMSE));
                bw.newLine();

                if (r.extendedCostResult != null) {
                    ExtendedCostResult e = r.extendedCostResult;
                    bw.write(String.format(Locale.US,
                            "ExtendedCosts: reactiveTotal=%.2f; proactiveTotal=%.2f; energyReactive=%.2f; energyProactive=%.2f; operational=%.2f; amortised=%.2f; saving=%.2f%%",
                            e.reactiveTotal, e.proactiveTotal, e.energyCostReactive, e.energyCostProactive, e.operationalCost, e.amortisedDeploymentCost, e.savingPercentage));
                    bw.newLine();
                    bw.write("ScalingCostReactiveBreakdown:\n");
                    for (Map.Entry<FaultType, Double> entry : e.scalingCostReactiveBreakdown.entrySet()) {
                        bw.write(entry.getKey().name() + "=" + String.format(Locale.US, "%.2f", entry.getValue()) + "\t");
                    }
                    bw.newLine();
                    bw.write("ScalingCostProactiveBreakdown:\n");
                    for (Map.Entry<FaultType, Double> entry : e.scalingCostProactiveBreakdown.entrySet()) {
                        bw.write(entry.getKey().name() + "=" + String.format(Locale.US, "%.2f", entry.getValue()) + "\t");
                    }
                    bw.newLine();
                }

                bw.write("ConfusionMatrix:");
                bw.newLine();
                for (int i = 0; i < r.confusion.length; i++) {
                    for (int j = 0; j < r.confusion[i].length; j++) {
                        bw.write(Integer.toString(r.confusion[i][j]));
                        if (j < r.confusion[i].length - 1) bw.write("\t");
                    }
                    bw.newLine();
                }
                bw.newLine();
            }
        }
    }
}
