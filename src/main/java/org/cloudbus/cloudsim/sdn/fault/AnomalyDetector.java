package org.cloudbus.cloudsim.sdn.fault;

@FunctionalInterface
public interface AnomalyDetector {
    AnomalyEvent detect(VmSample sample, int vmId, double timestamp);
}
