package org.cloudbus.cloudsim.sdn.fault;

import java.util.HashMap;
import java.util.Map;

public class BridgeByteStreakDetector implements AnomalyDetector {
    private static final int ZERO_OCTET_THRESHOLD = 10;

    @Override
    public AnomalyEvent detect(VmSample sample, int vmId, double timestamp) {
        if (sample.getZeroOctetCount() >= ZERO_OCTET_THRESHOLD) {
            Map<String, Object> metrics = new HashMap<>();
            metrics.put("zeroOctetCount", sample.getZeroOctetCount());
            return new AnomalyEvent(AnomalyType.BRIDGE_BYTE_STREAK, vmId, timestamp, metrics);
        }
        return new AnomalyEvent(AnomalyType.NONE, vmId, timestamp, new HashMap<>());
    }
}
