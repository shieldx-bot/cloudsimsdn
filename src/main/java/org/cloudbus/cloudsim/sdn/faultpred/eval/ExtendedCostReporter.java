package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

import java.util.Map;

public class ExtendedCostReporter {

    private static final String LINE = "=".repeat(72);

    public static void print(ExtendedCostResult result) {
        System.out.println();
        System.out.println(LINE);
        System.out.println("  EXTENDED COST EVALUATION REPORT");
        System.out.println(LINE);
        System.out.printf("  Simulation Duration          : %.2f hours%n", result.simulationDurationHours);
        System.out.println(LINE);
        System.out.println();
        System.out.println("  +----------------------+---------------+---------------+");
        System.out.println("  | Cost Component       | Reactive      | Proactive     |");
        System.out.println("  +----------------------+---------------+---------------+");
        System.out.printf("  | %-20s | %13.2f | %13.2f |%n",
                "Fault Penalty", result.faultPenaltyReactive, result.faultPenaltyProactive);
        System.out.printf("  | %-20s | %13.2f | %13.2f |%n",
                "Amortised Deployment", result.amortisedDeploymentCost, result.amortisedDeploymentCost);
        System.out.printf("  | %-20s | %13.2f | %13.2f |%n",
                "Operational", 0.0, result.operationalCost);
        System.out.printf("  | %-20s | %13.2f | %13.2f |%n",
                "Energy", result.energyCostReactive, result.energyCostProactive);
        System.out.printf("  | %-20s | %13.2f | %13.2f |%n",
                "Scaling", result.scalingCostReactive, result.scalingCostProactive);
        System.out.println("  +----------------------+---------------+---------------+");
        System.out.printf("  | %-20s | %13.2f | %13.2f |%n",
                "TOTAL", result.reactiveTotal, result.proactiveTotal);
        System.out.println("  +----------------------+---------------+---------------+");
        System.out.println();
        System.out.printf("  Resource Saving vs Original Model: %8.2f%%%n", result.savingPercentage);
        System.out.println();
        System.out.println(LINE);
    }

    public static void printScalingDetail(ExtendedCostResult result) {
        System.out.println();
        System.out.println("  === Scaling Cost Breakdown by Fault Type ===");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-25s %15s %15s%n", "FaultType", "Reactive", "Proactive"));
        sb.append("  " + "-".repeat(55) + "\n");
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            double r = result.scalingCostReactiveBreakdown.getOrDefault(ft, 0.0);
            double p = result.scalingCostProactiveBreakdown.getOrDefault(ft, 0.0);
            sb.append(String.format("%-25s %15.2f %15.2f%n", ft.name(), r, p));
        }
        sb.append("  " + "-".repeat(55) + "\n");
        sb.append(String.format("%-25s %15.2f %15.2f%n",
                "TOTAL SCALING", result.scalingCostReactive, result.scalingCostProactive));
        System.out.print(sb.toString());
        System.out.println();
    }

    public static void printPerFaultPenaltyDetail(ExtendedCostResult result) {
        System.out.println();
        System.out.println("  === Fault Penalty Breakdown ===");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-25s %15s %15s%n", "FaultType", "Reactive", "Proactive"));
        sb.append("  " + "-".repeat(55) + "\n");
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            double r = ExtendedCostConfig.getFullCost(ft);
            double p = ExtendedCostConfig.getFullCost(ft) * ExtendedCostConfig.getMitigationCostRatio();
            sb.append(String.format("%-25s %15.2f %15.2f%n", ft.name(), r, p));
        }
        sb.append("  " + "-".repeat(55) + "\n");
        System.out.print(sb.toString());
        System.out.println();
    }
}
