package org.cloudbus.cloudsim.sdn.faultpred.eval;

import org.cloudbus.cloudsim.sdn.memory.FaultType;

public class ScalingAction {

    private final ScalingActionType type;
    private final double time;
    private final String hostName;
    private final String vmName;
    private final int vmId;
    private final double cpuDelta;      // +ve = scale up, -ve = scale down (in vCPUs)
    private final double memoryDeltaGb; // +ve = scale up, -ve = scale down
    private final double mipsDelta;     // MIPS added/removed
    private final double durationSec;   // how long the scalable resource was active
    private final String description;
    /**
     * The fault this scaling action is mitigating. May be null for actions not
     * tied to a specific fault (e.g. proactive baseline resizing). Required so
     * the cost model can attribute the cost to the correct fault bucket instead
     * of lumping everything into VCPU_OVERLOAD_START.
     */
    private final FaultType faultType;

    public ScalingAction(ScalingActionType type, double time, String hostName,
                         String vmName, int vmId, double cpuDelta, double memoryDeltaGb,
                         double mipsDelta, double durationSec, String description) {
        this(type, time, hostName, vmName, vmId, cpuDelta, memoryDeltaGb,
                mipsDelta, durationSec, description, null);
    }

    public ScalingAction(ScalingActionType type, double time, String hostName,
                         String vmName, int vmId, double cpuDelta, double memoryDeltaGb,
                         double mipsDelta, double durationSec, String description,
                         FaultType faultType) {
        this.type = type;
        this.time = time;
        this.hostName = hostName;
        this.vmName = vmName;
        this.vmId = vmId;
        this.cpuDelta = cpuDelta;
        this.memoryDeltaGb = memoryDeltaGb;
        this.mipsDelta = mipsDelta;
        this.durationSec = durationSec;
        this.description = description;
        this.faultType = faultType;
    }

    public ScalingActionType getType() { return type; }
    public double getTime() { return time; }
    public String getHostName() { return hostName; }
    public String getVmName() { return vmName; }
    public int getVmId() { return vmId; }
    public double getCpuDelta() { return cpuDelta; }
    public double getMemoryDeltaGb() { return memoryDeltaGb; }
    public double getMipsDelta() { return mipsDelta; }
    public double getDurationSec() { return durationSec; }
    public String getDescription() { return description; }
    public FaultType getFaultType() { return faultType; }
}
