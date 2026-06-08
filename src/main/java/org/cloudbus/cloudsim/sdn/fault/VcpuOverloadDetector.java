package org.cloudbus.cloudsim.sdn.fault;

import java.util.HashMap;
import java.util.Map;

public class VcpuOverloadDetector implements AnomalyDetector {
    private static final double USER_THRESHOLD = 0.80;
    private static final double SYSTEM_THRESHOLD = 0.15;

    @Override
    public AnomalyEvent detect(VmSample sample, int vmId, double timestamp) {
        double cpuUtil = sample.getCpuUtil();
        if (cpuUtil >= USER_THRESHOLD) {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("cpuUtil", cpuUtil);
            metrics.put("memUsedPercent", sample.getMemUsedPercent());
            return new AnomalyEvent(AnomalyType.VCPU_OVERLOAD_START, vmId, timestamp, metrics);
        }
        return new AnomalyEvent(AnomalyType.NONE, vmId, timestamp, new HashMap<>());
    }
}
