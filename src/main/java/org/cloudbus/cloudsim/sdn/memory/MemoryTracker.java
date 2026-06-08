package org.cloudbus.cloudsim.sdn.memory;

import java.util.HashMap;
import java.util.Map;

public class MemoryTracker {
    private static final Map<Integer, MemoryMonitor> monitors = new HashMap<>();

    public static void record(int vmId, double timestamp, double usedPercent, long totalMemBytes) {
        monitors.computeIfAbsent(vmId, MemoryMonitor::new)
                .record(timestamp, usedPercent, totalMemBytes);
    }

    public static MemoryMonitor getMonitor(int vmId) {
        return monitors.get(vmId);
    }

    public static void clear() {
        monitors.clear();
    }
}
