package org.cloudbus.cloudsim.sdn.faultpred.pipeline;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

public class TimeSample {
    public final double[] metrics;
    public final int faultLabel;
    public final FaultType faultType;

    public TimeSample(double[] metrics, int faultLabel) {
        this.metrics = metrics;
        this.faultLabel = faultLabel;
        this.faultType = FaultType.fromValue(faultLabel);
    }
}
