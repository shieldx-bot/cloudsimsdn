package org.cloudbus.cloudsim.sdn.fault;

public class VmSample {
    private final double cpuUtil;
    private final double memUsedPercent;
    private final long memTotalBytes;
    private final double bwDataRate;
    private final int operStatus;
    private final int zeroOctetCount;

    public VmSample(double cpuUtil, double memUsedPercent, long memTotalBytes,
                    double bwDataRate, int operStatus, int zeroOctetCount) {
        this.cpuUtil = cpuUtil;
        this.memUsedPercent = memUsedPercent;
        this.memTotalBytes = memTotalBytes;
        this.bwDataRate = bwDataRate;
        this.operStatus = operStatus;
        this.zeroOctetCount = zeroOctetCount;
    }

    public double getCpuUtil() {
        return cpuUtil;
    }

    public double getMemUsedPercent() {
        return memUsedPercent;
    }

    public long getMemTotalBytes() {
        return memTotalBytes;
    }

    public double getBwDataRate() {
        return bwDataRate;
    }

    public int getOperStatus() {
        return operStatus;
    }

    public int getZeroOctetCount() {
        return zeroOctetCount;
    }
}
