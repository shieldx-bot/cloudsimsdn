package org.cloudbus.cloudsim.sdn.memory;

public class MemoryData {
    private final double timestamp;
    private final double usedPercent;
    private final long totalMemBytes;

    public MemoryData(double timestamp, double usedPercent, long totalMemBytes) {
        this.timestamp = timestamp;
        this.usedPercent = usedPercent;
        this.totalMemBytes = totalMemBytes;
    }

    public double getTimestamp() {
        return timestamp;
    }

    public double getUsedPercent() {
        return usedPercent;
    }

    public long getTotalMemBytes() {
        return totalMemBytes;
    }
}
