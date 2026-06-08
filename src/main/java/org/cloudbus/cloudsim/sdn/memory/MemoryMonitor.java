package org.cloudbus.cloudsim.sdn.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MemoryMonitor {
    private final int vmId;
    private final List<MemoryData> snapshots;

    public MemoryMonitor(int vmId) {
        this.vmId = vmId;
        this.snapshots = new ArrayList<>();
    }

    public synchronized void record(double timestamp, double usedPercent, long totalMemBytes) {
        snapshots.add(new MemoryData(timestamp, usedPercent, totalMemBytes));
    }

    public synchronized List<MemoryData> getSnapshots() {
        return Collections.unmodifiableList(snapshots);
    }

    public synchronized MemoryData getLatest() {
        if (snapshots.isEmpty()) return null;
        return snapshots.get(snapshots.size() - 1);
    }

    public int getVmId() {
        return vmId;
    }
}
