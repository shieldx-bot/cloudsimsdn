package org.cloudbus.cloudsim.sdn.fault;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class AnomalyAnalyzer {
    private final AnomalyDetector memoryDetector;
    private final AnomalyDetector bridgeDetector;
    private final AnomalyDetector vcpuDetector;
    private final AnomalyDetector interfaceDetector;

    public AnomalyAnalyzer() {
        this.memoryDetector = new MemoryStressDetector();
        this.bridgeDetector = new BridgeByteStreakDetector();
        this.vcpuDetector = new VcpuOverloadDetector();
        this.interfaceDetector = new InterfaceDetector();
    }

    public AnomalyEvent analyze(VmSample sample, int vmId, double timestamp) {
        AnomalyEvent event = vcpuDetector.detect(sample, vmId, timestamp);
        if (!event.isAnomaly()) event = memoryDetector.detect(sample, vmId, timestamp);
        if (!event.isAnomaly()) event = bridgeDetector.detect(sample, vmId, timestamp);
        if (!event.isAnomaly()) event = interfaceDetector.detect(sample, vmId, timestamp);
        return event;
    }
}
