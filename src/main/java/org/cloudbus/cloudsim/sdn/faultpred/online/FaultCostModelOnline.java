package org.cloudbus.cloudsim.sdn.faultpred.online;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

import java.util.EnumMap;
import java.util.Map;

public class FaultCostModelOnline {

    public static final double CPU_COST_PER_MI = 0.001;
    public static final double BW_COST_PER_BYTE = 1e-9;

    private final EnumMap<FaultType, Double> reactiveCpuTime;
    private final EnumMap<FaultType, Double> proactiveCpuTime;
    private final EnumMap<FaultType, Double> reactiveBwBytes;
    private final EnumMap<FaultType, Double> proactiveBwBytes;

    public FaultCostModelOnline() {
        this.reactiveCpuTime = new EnumMap<>(FaultType.class);
        this.proactiveCpuTime = new EnumMap<>(FaultType.class);
        this.reactiveBwBytes = new EnumMap<>(FaultType.class);
        this.proactiveBwBytes = new EnumMap<>(FaultType.class);
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            reactiveCpuTime.put(ft, 0.0);
            proactiveCpuTime.put(ft, 0.0);
            reactiveBwBytes.put(ft, 0.0);
            proactiveBwBytes.put(ft, 0.0);
        }
    }

    public void recordReactiveResource(FaultType ft, double cpuTime, double bwBytes) {
        if (ft == FaultType.NONE) return;
        reactiveCpuTime.merge(ft, cpuTime, Double::sum);
        reactiveBwBytes.merge(ft, bwBytes, Double::sum);
    }

    public void recordProactiveResource(FaultType ft, double cpuTime, double bwBytes) {
        if (ft == FaultType.NONE) return;
        proactiveCpuTime.merge(ft, cpuTime, Double::sum);
        proactiveBwBytes.merge(ft, bwBytes, Double::sum);
    }

    public double computePerFaultTypeReactiveCost(FaultType ft) {
        if (ft == FaultType.NONE) return 0.0;
        return reactiveCpuTime.getOrDefault(ft, 0.0) * CPU_COST_PER_MI
             + reactiveBwBytes.getOrDefault(ft, 0.0) * BW_COST_PER_BYTE;
    }

    public double computePerFaultTypeProactiveCost(FaultType ft) {
        if (ft == FaultType.NONE) return 0.0;
        return proactiveCpuTime.getOrDefault(ft, 0.0) * CPU_COST_PER_MI
             + proactiveBwBytes.getOrDefault(ft, 0.0) * BW_COST_PER_BYTE;
    }

    public double getReactiveTotalCost() {
        double total = 0.0;
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            total += computePerFaultTypeReactiveCost(ft);
        }
        return total;
    }

    public double getProactiveTotalCost() {
        double total = 0.0;
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            total += computePerFaultTypeProactiveCost(ft);
        }
        return total;
    }

    public double getSavingPercentage() {
        double reactive = getReactiveTotalCost();
        if (reactive == 0.0) return 0.0;
        return (reactive - getProactiveTotalCost()) / reactive * 100.0;
    }

    public Map<FaultType, Double> getPerFaultTypeReactiveCost() {
        EnumMap<FaultType, Double> map = new EnumMap<>(FaultType.class);
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            map.put(ft, computePerFaultTypeReactiveCost(ft));
        }
        return map;
    }

    public Map<FaultType, Double> getPerFaultTypeProactiveCost() {
        EnumMap<FaultType, Double> map = new EnumMap<>(FaultType.class);
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            map.put(ft, computePerFaultTypeProactiveCost(ft));
        }
        return map;
    }
}
