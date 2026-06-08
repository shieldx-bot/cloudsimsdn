package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

import java.util.HashMap;
import java.util.Map;

public class FaultCostModel {

    private final Map<FaultType, Double> fullFaultCostMap;
    private final double mitigationCostRatio;
    private final double falseAlarmCost;

    public FaultCostModel() {
        this(0.20);
    }

    public FaultCostModel(double mitigationCostRatio) {
        this.fullFaultCostMap = new HashMap<>();
        this.mitigationCostRatio = mitigationCostRatio;
        this.falseAlarmCost = computeAverageFaultCost() * 0.10;
        initCosts();
    }

    private double computeAverageFaultCost() {
        double sum = 0;
        int count = 0;
        for (Map.Entry<FaultType, Double> e : fullFaultCostMap.entrySet()) {
            sum += e.getValue();
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private void initCosts() {
        fullFaultCostMap.put(FaultType.BRIDGE_BYTE_STREAK, 200.0);
        fullFaultCostMap.put(FaultType.INTERFACE_DOWN, 500.0);
        fullFaultCostMap.put(FaultType.INTERFACE_LOSS_START, 300.0);
        fullFaultCostMap.put(FaultType.MEMORY_STRESS_START, 400.0);
        fullFaultCostMap.put(FaultType.VCPU_OVERLOAD_START, 450.0);
    }

    public double getFullCost(FaultType ft) {
        return fullFaultCostMap.getOrDefault(ft, 150.0);
    }

    public double getMitigationCost(FaultType ft) {
        return getFullCost(ft) * mitigationCostRatio;
    }

    public double getFalseAlarmCost() {
        return falseAlarmCost;
    }

    public double calculateReactiveCost(long numFaults, FaultType ft) {
        return numFaults * getFullCost(ft);
    }

    public double calculateProactiveCost(long tp, long fp, long fn, FaultType ft) {
        double total = (tp * getMitigationCost(ft))
                     + (fp * falseAlarmCost)
                     + (fn * getFullCost(ft));
        return total;
    }

    public static String formatCostTable() {
        double[] costs = {200.0, 500.0, 300.0, 400.0, 450.0};
        String[] names = {"bridge-delif", "interface-down", "interface-loss-start",
                          "memory-stress-start", "vcpu-overload-start"};
        double avg = (200 + 500 + 300 + 400 + 450) / 5.0;
        double fa = avg * 0.10;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-25s %10s %15s %15s%n", "FaultType", "FullCost", "Mitigation(20%)", "FalseAlarm(10%)"));
        sb.append("------------------------------------------------------------------------\n");
        for (int i = 0; i < names.length; i++) {
            sb.append(String.format("%-25s %10.1f %15.1f %15.1f%n",
                names[i], costs[i], costs[i] * 0.20, fa));
        }
        return sb.toString();
    }
}
