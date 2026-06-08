package org.cloudbus.cloudsim.sdn.fault;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class AnomalyEvent {
    private final AnomalyType type;
    private final int vmId;
    private final double timestamp;
    private final Map<String, Object> metrics;

    public AnomalyEvent(AnomalyType type, int vmId, double timestamp) {
        this(type, vmId, timestamp, Collections.emptyMap());
    }

    public AnomalyEvent(AnomalyType type, int vmId, double timestamp, Map<String, Object> metrics) {
        this.type = type;
        this.vmId = vmId;
        this.timestamp = timestamp;
        this.metrics = Collections.unmodifiableMap(new HashMap<>(metrics));
    }

    public AnomalyType getType() {
        return type;
    }

    public int getVmId() {
        return vmId;
    }

    public double getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getMetrics() {
        return metrics;
    }

    public boolean isAnomaly() {
        return type != AnomalyType.NONE;
    }
}
