package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

import java.util.EnumMap;
import java.util.Map;

public class ExtendedCostResult {

    public final double reactiveTotal;
    public final double proactiveTotal;
    public final double savingPercentage;
    public final double amortisedDeploymentCost;
    public final double operationalCost;
    public final double energyCostReactive;
    public final double energyCostProactive;
    public final double scalingCostReactive;
    public final double scalingCostProactive;
    public final double faultPenaltyReactive;
    public final double faultPenaltyProactive;
    public final Map<FaultType, Double> scalingCostReactiveBreakdown;
    public final Map<FaultType, Double> scalingCostProactiveBreakdown;
    public final double simulationDurationHours;

    public ExtendedCostResult(double reactiveTotal, double proactiveTotal, double savingPercentage,
                              double amortisedDeploymentCost, double operationalCost,
                              double energyCostReactive, double energyCostProactive,
                              double scalingCostReactive, double scalingCostProactive,
                              double faultPenaltyReactive, double faultPenaltyProactive,
                              Map<FaultType, Double> scalingCostReactiveBreakdown,
                              Map<FaultType, Double> scalingCostProactiveBreakdown,
                              double simulationDurationHours) {
        this.reactiveTotal = reactiveTotal;
        this.proactiveTotal = proactiveTotal;
        this.savingPercentage = savingPercentage;
        this.amortisedDeploymentCost = amortisedDeploymentCost;
        this.operationalCost = operationalCost;
        this.energyCostReactive = energyCostReactive;
        this.energyCostProactive = energyCostProactive;
        this.scalingCostReactive = scalingCostReactive;
        this.scalingCostProactive = scalingCostProactive;
        this.faultPenaltyReactive = faultPenaltyReactive;
        this.faultPenaltyProactive = faultPenaltyProactive;
        this.scalingCostReactiveBreakdown = scalingCostReactiveBreakdown;
        this.scalingCostProactiveBreakdown = scalingCostProactiveBreakdown;
        this.simulationDurationHours = simulationDurationHours;
    }
}
