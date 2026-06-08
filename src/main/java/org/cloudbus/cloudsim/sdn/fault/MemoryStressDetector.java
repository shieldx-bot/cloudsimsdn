package org.cloudbus.cloudsim.sdn.fault;

import org.cloudbus.cloudsim.sdn.Configuration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MemoryStressDetector implements AnomalyDetector {
    private static final double MEM_STATUS_CRITICAL = 1.0;
    private static final double USED_PERCENT_MEMORY_THRESHOLD = 0.90;

    @Override
    public AnomalyEvent detect(VmSample sample, int vmId, double timestamp) {
        if (sample.getMemUsedPercent() >= USED_PERCENT_MEMORY_THRESHOLD) {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("usedPercent", sample.getMemUsedPercent());
            metrics.put("totalMemBytes", sample.getMemTotalBytes());
            return new AnomalyEvent(AnomalyType.MEMORY_STRESS_START, vmId, timestamp, metrics);
        }
        return new AnomalyEvent(AnomalyType.NONE, vmId, timestamp, Collections.emptyMap());
    }
}
