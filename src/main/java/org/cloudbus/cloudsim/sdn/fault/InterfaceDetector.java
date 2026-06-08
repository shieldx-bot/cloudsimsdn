package org.cloudbus.cloudsim.sdn.fault;

public class InterfaceDetector implements AnomalyDetector {
    private int downVmid = -1;

    @Override
    public AnomalyEvent detect(VmSample sample, int vmId, double timestamp) {
        if (sample.getOperStatus() == 1) {
            if (downVmid != vmId) {
                downVmid = vmId;
                return new AnomalyEvent(AnomalyType.INTERFACE_DOWN, vmId, timestamp);
            }
            return new AnomalyEvent(AnomalyType.INTERFACE_LOSS_START, vmId, timestamp);
        }
        downVmid = -1;
        return new AnomalyEvent(AnomalyType.NONE, vmId, timestamp);
    }
}
