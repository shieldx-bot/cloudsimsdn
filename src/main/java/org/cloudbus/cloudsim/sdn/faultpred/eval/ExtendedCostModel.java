package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ExtendedCostModel {

    private final double simulationDurationHours;
    private final double proactiveAgentCpuTimeSec;
    private final double proactiveAgentMemoryGbSec;
    private final double proactiveAgentBwGb;
    private final EnumMap<FaultType, Integer> proactiveMitigationCount;
    private final EnumMap<FaultType, Integer> reactiveFaultCount;
    private final List<ScalingAction> scalingActions;
    private final double totalHostUtilizationSum;
    private final int hostUtilizationSamples;
    private final double totalHostPeMips;
    private final double hostMemoryGb;

    public ExtendedCostModel(double simulationDurationHours,
                             double proactiveAgentCpuTimeSec,
                             double proactiveAgentMemoryGbSec,
                             double proactiveAgentBwGb,
                             EnumMap<FaultType, Integer> proactiveMitigationCount,
                             EnumMap<FaultType, Integer> reactiveFaultCount,
                             List<ScalingAction> scalingActions,
                             double totalHostUtilizationSum,
                             int hostUtilizationSamples,
                             double totalHostPeMips,
                             double hostMemoryGb) {
        this.simulationDurationHours = simulationDurationHours;
        this.proactiveAgentCpuTimeSec = proactiveAgentCpuTimeSec;
        this.proactiveAgentMemoryGbSec = proactiveAgentMemoryGbSec;
        this.proactiveAgentBwGb = proactiveAgentBwGb;
        this.proactiveMitigationCount = proactiveMitigationCount != null ? proactiveMitigationCount : new EnumMap<>(FaultType.class);
        this.reactiveFaultCount = reactiveFaultCount != null ? reactiveFaultCount : new EnumMap<>(FaultType.class);
        this.scalingActions = scalingActions != null ? scalingActions : new ArrayList<>();
        this.totalHostUtilizationSum = totalHostUtilizationSum;
        this.hostUtilizationSamples = hostUtilizationSamples;
        this.totalHostPeMips = totalHostPeMips;
        this.hostMemoryGb = hostMemoryGb;
    }

    public ExtendedCostResult compute() {
        // 1. Count per-fault actuals and predictions for the per-class breakdown
        int[] actualPerFault = new int[FaultType.values().length];
        int[] predictedPerFault = new int[FaultType.values().length];
        int[] truePositivesPerFault = new int[FaultType.values().length];
        int[] falsePositivesPerFault = new int[FaultType.values().length];
        int[] falseNegativesPerFault = new int[FaultType.values().length];

        // The fault counts are stored in the EnumMap (keyed by FaultType). We
        // assume reactiveFaultCount[i] is the count of fault i that actually
        // occurred, and proactiveMitigationCount[i] is the count of fault i
        // that the predictor flagged. A prediction is "correct" (TP) only if
        // there is a matching actual fault; remaining predicted events are FP
        // and remaining actual events are FN.
        int totalReactiveFaults = 0;
        int totalPredictedFaults = 0;
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            int actual = reactiveFaultCount.getOrDefault(ft, 0);
            int predicted = proactiveMitigationCount.getOrDefault(ft, 0);
            actualPerFault[ft.ordinal()] = actual;
            predictedPerFault[ft.ordinal()] = predicted;
            truePositivesPerFault[ft.ordinal()] = Math.min(actual, predicted);
            falsePositivesPerFault[ft.ordinal()] = Math.max(0, predicted - actual);
            falseNegativesPerFault[ft.ordinal()] = Math.max(0, actual - predicted);
            totalReactiveFaults += actual;
            totalPredictedFaults += predicted;
        }

        // 2. Reactive cost: pay full price for ALL actual faults (no prediction)
        double reactiveFaultPenalty = 0.0;
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            reactiveFaultPenalty += actualPerFault[ft.ordinal()]
                    * ExtendedCostConfig.getFullCost(ft);
        }

        // 3. Proactive cost: pay mitigated price for TPs, false-alarm cost for FPs,
        //    full price for FNs (missed faults still hurt at full price)
        double proactiveFaultPenalty = 0.0;
        double falseAlarmCost = 0.0;
        double missedFaultCost = 0.0;
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            int tp = truePositivesPerFault[ft.ordinal()];
            int fp = falsePositivesPerFault[ft.ordinal()];
            int fn = falseNegativesPerFault[ft.ordinal()];
            proactiveFaultPenalty += tp * ExtendedCostConfig.getFullCost(ft)
                    * ExtendedCostConfig.getMitigationCostRatio();
            falseAlarmCost += fp * ExtendedCostConfig.getFullCost(ft)
                    * ExtendedCostConfig.getFalseAlarmCostRatio();
            missedFaultCost += fn * ExtendedCostConfig.getFullCost(ft);
        }
        double proactiveFaultPenaltyTotal = proactiveFaultPenalty + falseAlarmCost + missedFaultCost;

        double amortisedDeploymentCost = simulationDurationHours > 0
                ? ExtendedCostConfig.getDeploymentCost() / simulationDurationHours
                : 0.0;

        double operationalCost = computeOperationalCost();

        // Energy cost uses the actual reactive fault penalty as the multiplier
        // (a system that runs with no predictor pays the full energy tax when
        // a fault happens and the system runs degraded for longer).
        double energyCostReactive = computeEnergyCost(reactiveFaultPenalty);
        // For the proactive path, only the missed-fault cost contributes the
        // energy penalty, since successfully mitigated faults don't degrade
        // performance for the full duration.
        double energyCostProactive = computeEnergyCost(missedFaultCost);

        ScalingCostResult scalingCosts = computeScalingCosts();

        double reactiveTotal = reactiveFaultPenalty
                + amortisedDeploymentCost
                + 0.0
                + energyCostReactive
                + scalingCosts.reactiveTotal;

        double proactiveTotal = proactiveFaultPenaltyTotal
                + amortisedDeploymentCost
                + operationalCost
                + energyCostProactive
                + scalingCosts.proactiveTotal;

        double saving = reactiveTotal > 0 ? (reactiveTotal - proactiveTotal) / reactiveTotal * 100.0 : 0.0;

        return new ExtendedCostResult(
                reactiveTotal, proactiveTotal, saving,
                amortisedDeploymentCost, operationalCost,
                energyCostReactive, energyCostProactive,
                scalingCosts.reactiveTotal, scalingCosts.proactiveTotal,
                reactiveFaultPenalty, proactiveFaultPenaltyTotal,
                scalingCosts.reactiveBreakdown, scalingCosts.proactiveBreakdown,
                simulationDurationHours);
    }

    private double computeReactiveFaultPenalty() {
        double total = 0.0;
        for (Map.Entry<FaultType, Integer> entry : reactiveFaultCount.entrySet()) {
            total += entry.getValue() * ExtendedCostConfig.getFullCost(entry.getKey());
        }
        return total;
    }

    private double computeProactiveFaultPenalty() {
        double total = 0.0;
        for (Map.Entry<FaultType, Integer> entry : proactiveMitigationCount.entrySet()) {
            total += entry.getValue() * ExtendedCostConfig.getFullCost(entry.getKey()) * ExtendedCostConfig.getMitigationCostRatio();
        }
        return total;
    }

    private double computeOperationalCost() {
        double cpuCost = proactiveAgentCpuTimeSec / 3600.0 * ExtendedCostConfig.getCpuCostPerVcpuHour();
        double memCost = proactiveAgentMemoryGbSec / (1024.0 * 3600.0) * ExtendedCostConfig.getMemoryCostPerGbHour();
        double bwCost = proactiveAgentBwGb * ExtendedCostConfig.getBwCostPerGbHour();
        return cpuCost + memCost + bwCost;
    }

    private double computeEnergyCost(double faultPenalty) {
        if (hostUtilizationSamples == 0 || totalHostPeMips <= 0) {
            return 0.0;
        }
        double avgUtil = totalHostUtilizationSum / hostUtilizationSamples;
        double pIdle = ExtendedCostConfig.getHostPowerIdleWatts();
        double pMax = ExtendedCostConfig.getHostPowerMaxWatts();
        double avgPower = pIdle + (pMax - pIdle) * Math.min(avgUtil, ExtendedCostConfig.getHostPeakCpuUtil());
        double durationHours = simulationDurationHours;
        double energyKwh = (avgPower * durationHours) / 1000.0;
        double baseEnergyCost = energyKwh * ExtendedCostConfig.getEnergyPricePerKwh();
        double faultMultiplier = 1.0 + (faultPenalty / 1000.0);
        return baseEnergyCost * faultMultiplier;
    }

    private ScalingCostResult computeScalingCosts() {
        double proactiveTotal = 0.0;
        double reactiveTotal = 0.0;
        EnumMap<FaultType, Double> proactiveBreakdown = new EnumMap<>(FaultType.class);
        EnumMap<FaultType, Double> reactiveBreakdown = new EnumMap<>(FaultType.class);
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            proactiveBreakdown.put(ft, 0.0);
            reactiveBreakdown.put(ft, 0.0);
        }

        for (ScalingAction action : scalingActions) {
            double cost = computeScalingActionCost(action);
            // Use the action's own fault type when available so the cost is
            // attributed to the right fault bucket. Fall back to NONE for
            // actions that are not tied to a specific fault.
            FaultType ft = action.getFaultType() != null ? action.getFaultType() : FaultType.NONE;
            if (ft == FaultType.NONE) {
                // Cannot attribute to a specific fault; charge the proactive
                // bucket as a global mitigation overhead.
                proactiveTotal += cost;
                continue;
            }
            if (action.getType() == ScalingActionType.SCALE_UP
                    || action.getType() == ScalingActionType.SCALE_DOWN) {
                if (proactiveMitigationCount.containsKey(ft) && proactiveMitigationCount.get(ft) > 0) {
                    proactiveTotal += cost;
                    proactiveBreakdown.put(ft, proactiveBreakdown.getOrDefault(ft, 0.0) + cost);
                } else if (reactiveFaultCount.containsKey(ft) && reactiveFaultCount.get(ft) > 0) {
                    reactiveTotal += cost;
                    reactiveBreakdown.put(ft, reactiveBreakdown.getOrDefault(ft, 0.0) + cost);
                }
            } else {
                proactiveTotal += cost;
                proactiveBreakdown.put(ft, proactiveBreakdown.getOrDefault(ft, 0.0) + cost);
            }
        }

        int totalReactiveFaults = reactiveFaultCount.values().stream().mapToInt(Integer::intValue).sum();
        double estimateReactiveScaleCost = totalReactiveFaults * ExtendedCostConfig.getMigrationCostFixed() * 0.5;
        reactiveTotal += estimateReactiveScaleCost;

        return new ScalingCostResult(reactiveTotal, proactiveTotal, reactiveBreakdown, proactiveBreakdown);
    }

    private double computeScalingActionCost(ScalingAction action) {
        double cost = 0.0;
        switch (action.getType()) {
            case SCALE_UP:
                cost += action.getCpuDelta() * ExtendedCostConfig.getScaleUpCpuCostPerVcpu();
                cost += action.getMemoryDeltaGb() * ExtendedCostConfig.getScaleUpMemCostPerGb();
                double activeHours = action.getDurationSec() / 3600.0;
                cost *= activeHours;
                break;
            case SCALE_DOWN:
                cost += ExtendedCostConfig.getScaleDownCostFixed();
                break;
            case MIGRATION:
                cost += ExtendedCostConfig.getMigrationCostFixed();
                cost += ExtendedCostConfig.getMigrationCostPerVm();
                break;
            case REROUTE:
                cost += action.getMipsDelta() * 0.01;
                break;
        }
        return cost;
    }

    private static class ScalingCostResult {
        final double reactiveTotal;
        final double proactiveTotal;
        final Map<FaultType, Double> reactiveBreakdown;
        final Map<FaultType, Double> proactiveBreakdown;

        ScalingCostResult(double reactiveTotal, double proactiveTotal,
                          Map<FaultType, Double> reactiveBreakdown,
                          Map<FaultType, Double> proactiveBreakdown) {
            this.reactiveTotal = reactiveTotal;
            this.proactiveTotal = proactiveTotal;
            this.reactiveBreakdown = reactiveBreakdown;
            this.proactiveBreakdown = proactiveBreakdown;
        }
    }
}
