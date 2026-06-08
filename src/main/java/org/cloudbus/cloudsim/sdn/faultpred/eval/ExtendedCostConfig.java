package org.cloudbus.cloudsim.sdn.faultpred.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

public class ExtendedCostConfig {

    private static final String PROPERTY_FILE = "extended-cost.properties";
    private static final Properties PROPS = new Properties();

    static {
        try (InputStream is = ExtendedCostConfig.class.getClassLoader()
                .getResourceAsStream(PROPERTY_FILE)) {
            if (is != null) {
                PROPS.load(is);
            }
        } catch (IOException e) {
            System.err.println("Failed to load " + PROPERTY_FILE + ": " + e.getMessage());
        }
    }

    private ExtendedCostConfig() {}

    public static double getDouble(String key, double defaultValue) {
        String val = PROPS.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static int getInt(String key, int defaultValue) {
        String val = PROPS.getProperty(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static String getString(String key, String defaultValue) {
        return PROPS.getProperty(key, defaultValue);
    }

    public static double getDeploymentCost() { return getDouble("deployment.cost", 500.0); }
    public static double getCpuCostPerVcpuHour() { return getDouble("cpu.cost.per.vcpu.hour", 0.05); }
    public static double getMemoryCostPerGbHour() { return getDouble("memory.cost.per.gb.hour", 0.01); }
    public static double getBwCostPerGbHour() { return getDouble("bw.cost.per.gb.hour", 0.02); }
    public static double getEnergyPricePerKwh() { return getDouble("energy.price.per.kwh", 0.10); }
    public static double getHostPowerIdleWatts() { return getDouble("host.power.idle.watts", 120.0); }
    public static double getHostPowerMaxWatts() { return getDouble("host.power.max.watts", 274.0); }
    public static double getHostPeakCpuUtil() { return getDouble("host.peak.cpu.util", 1.0); }
    public static double getScaleUpCpuCostPerVcpu() { return getDouble("scale.up.cpu.cost.per.vcpu", 0.10); }
    public static double getScaleUpMemCostPerGb() { return getDouble("scale.up.mem.cost.per.gb", 0.05); }
    public static double getMigrationCostFixed() { return getDouble("migration.cost.fixed", 50.0); }
    public static double getMigrationCostPerVm() { return getDouble("migration.cost.per.vm", 25.0); }
    public static double getScaleDownCostFixed() { return getDouble("scale.down.cost.fixed", 5.0); }
    public static double getMitigationCostRatio() { return getDouble("mitigation.cost.ratio", 0.20); }
    public static double getFalseAlarmCostRatio() { return getDouble("false.alarm.cost.ratio", 0.10); }

    public static double getFullCost(FaultType ft) {
        switch (ft) {
            case BRIDGE_BYTE_STREAK:   return getDouble("full.cost.bridge-byte-streak", 200.0);
            case INTERFACE_DOWN:       return getDouble("full.cost.interface-down", 500.0);
            case INTERFACE_LOSS_START: return getDouble("full.cost.interface-loss-start", 300.0);
            case MEMORY_STRESS_START:  return getDouble("full.cost.memory-stress-start", 400.0);
            case VCPU_OVERLOAD_START:  return getDouble("full.cost.vcpu-overload-start", 450.0);
            default:                   return 150.0;
        }
    }
}
