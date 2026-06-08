package org.cloudbus.cloudsim.sdn.faultpred.online;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.CloudSimTagsSDN;
import org.cloudbus.cloudsim.sdn.Configuration;
import org.cloudbus.cloudsim.sdn.memory.FaultType;
import org.cloudbus.cloudsim.sdn.nos.NetworkOperatingSystem;
import org.cloudbus.cloudsim.sdn.virtualcomponents.SDNVm;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ScalingAction;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ScalingActionType;

public class ProactiveMitigator extends SimEntity {

    private final NetworkOperatingSystem nos;
    private final Map<Integer, String> labelToDescription;
    private final int[] vmIds;
    private final String[] vmNames;
    private final Map<FaultType, Integer> mitigationCount;
    private final List<ScalingAction> scalingActions;
    private final String hostName;

    private ProactiveMitigator(String name, NetworkOperatingSystem nos,
                              int udmVmId, int amfVmId, int ausfVmId,
                              String hostName,
                              List<ScalingAction> scalingActions,
                              Map<FaultType, Integer> mitigationCount,
                              Map<Integer, String> labelToDescription) {
        super(name);
        this.nos = nos;
        this.vmIds = new int[] { udmVmId, amfVmId, ausfVmId };
        this.vmNames = new String[] { "udm", "amf", "ausf" };
        this.mitigationCount = mitigationCount;
        this.labelToDescription = labelToDescription;
        this.scalingActions = scalingActions != null ? scalingActions : new ArrayList<>();
        this.hostName = hostName;
    }

    public static Builder builder(String name, NetworkOperatingSystem nos,
                                   int udmVmId, int amfVmId, int ausfVmId) {
        return new Builder(name, nos, udmVmId, amfVmId, ausfVmId);
    }

    public List<ScalingAction> getScalingActions() {
        return Collections.unmodifiableList(scalingActions);
    }

    public Map<FaultType, Integer> getMitigationCount() {
        return Collections.unmodifiableMap(mitigationCount);
    }

    @Override
    public void startEntity() {
    }

    @Override
    public void shutdownEntity() {
        org.cloudbus.cloudsim.Log.printLine(CloudSim.clock() + ": " + getName() + " mitigation summary:");
        for (FaultType ft : FaultType.values()) {
            if (ft == FaultType.NONE) continue;
            int count = mitigationCount.getOrDefault(ft, 0);
            org.cloudbus.cloudsim.Log.printLine(String.format("  %s: %d", ft.name(), count));
        }
        org.cloudbus.cloudsim.Log.printLine("Total scaling actions: " + scalingActions.size());
    }

    @Override
    public void processEvent(SimEvent ev) {
        int tag = ev.getTag();

        if (tag != CloudSimTagsSDN.PROACTIVE_MITIGATE) {
            return;
        }

        Object data = ev.getData();
        if (data == null) return;

        int predictedLabel;
        int vmId;
        if (data instanceof int[]) {
            int[] arr = (int[]) data;
            if (arr.length < 2) return;
            predictedLabel = arr[0];
            vmId = arr[1];
        } else if (data instanceof SimEvent) {
            SimEvent inner = (SimEvent) data;
            Object innerData = inner.getData();
            if (!(innerData instanceof int[])) return;
            int[] arr = (int[]) innerData;
            if (arr.length < 2) return;
            predictedLabel = arr[0];
            vmId = arr[1];
        } else {
            return;
        }

        String vmName = null;
        for (int i = 0; i < vmIds.length; i++) {
            if (vmIds[i] == vmId) {
                vmName = vmNames[i];
                break;
            }
        }
        if (vmName == null) vmName = "vm-" + vmId;

        FaultType faultType = FaultType.fromValue(predictedLabel);
        if (faultType == FaultType.NONE) return;

        double now = CloudSim.clock();

        org.cloudbus.cloudsim.Log.printLine(now + ": " + getName()
                + " proactive mitigation triggered for " + vmName
                + " predicted fault=" + labelToDescription.getOrDefault(predictedLabel, String.valueOf(predictedLabel)));

        ScalingAction recordedAction = null;

        switch (predictedLabel) {
            case 5:
                recordedAction = mitigateVcpuOverload(vmId, vmName, now);
                break;
            case 4:
                recordedAction = mitigateMemoryStress(vmId, vmName, now);
                break;
            case 2:
            case 3:
                recordedAction = mitigateInterfaceIssue(vmId, vmName, now);
                break;
            case 1:
                recordedAction = mitigateBridgeDelif(vmId, vmName, now);
                break;
            default:
                break;
        }

        if (recordedAction != null) {
            scalingActions.add(recordedAction);
        }

        mitigationCount.put(faultType, mitigationCount.getOrDefault(faultType, 0) + 1);
    }

    private ScalingAction mitigateVcpuOverload(int vmId, String vmName, double time) {
        org.cloudbus.cloudsim.Log.printLine("Proactive: Requesting CPU scale-up for " + vmName);
        Vm vm = NetworkOperatingSystem.findVmGlobal(vmId);
        if (vm == null) return null;

        double currentMips = vm.getMips();
        double newMips = currentMips * 1.3;
        if (newMips <= 0) newMips = currentMips + 100;
        double mipsDelta = newMips - currentMips;

        SDNVm sdnVm = (SDNVm) vm;
        try {
            sdnVm.updatePeMips(sdnVm.getNumberOfPes(), newMips);
            org.cloudbus.cloudsim.Log.printLine("Proactive: Updated MIPS for " + vmName
                    + " from " + String.format("%.2f", currentMips)
                    + " to " + String.format("%.2f", newMips));
        } catch (Exception e) {
            org.cloudbus.cloudsim.Log.printLine("Proactive: Failed to update MIPS for " + vmName + ": " + e.getMessage());
        }

        nos.sendAdjustAllChannelEvent();

        return new ScalingAction(
                ScalingActionType.SCALE_UP,
                time,
                hostName,
                vmName,
                vmId,
                sdnVm != null ? sdnVm.getNumberOfPes() : vm.getNumberOfPes(),
                0.0,
                mipsDelta,
                Configuration.monitoringTimeInterval,
                "Vertical CPU scale-up to " + String.format("%.2f", newMips) + " MIPS"
        );
    }

    private ScalingAction mitigateMemoryStress(int vmId, String vmName, double time) {
        org.cloudbus.cloudsim.Log.printLine("Proactive: Requesting memory scale for " + vmName);
        return new ScalingAction(
                ScalingActionType.SCALE_UP,
                time,
                hostName,
                vmName,
                vmId,
                0.0,
                1.0,
                0.0,
                Configuration.monitoringTimeInterval,
                "Memory scale-up"
        );
    }

    private ScalingAction mitigateInterfaceIssue(int vmId, String vmName, double time) {
        org.cloudbus.cloudsim.Log.printLine("Proactive: Rerouting flows for " + vmName);
        nos.sendAdjustAllChannelEvent();
        return new ScalingAction(
                ScalingActionType.REROUTE,
                time,
                hostName,
                vmName,
                vmId,
                0.0,
                0.0,
                0.0,
                Configuration.monitoringTimeInterval,
                "Flow rerouting"
        );
    }

    private ScalingAction mitigateBridgeDelif(int vmId, String vmName, double time) {
        org.cloudbus.cloudsim.Log.printLine("Proactive: Requesting bandwidth increase for " + vmName);
        return new ScalingAction(
                ScalingActionType.SCALE_UP,
                time,
                hostName,
                vmName,
                vmId,
                0.0,
                0.0,
                0.0,
                Configuration.monitoringTimeInterval,
                "Bandwidth increase"
        );
    }

    public static class Builder {
        private final String name;
        private final NetworkOperatingSystem nos;
        private final int udmVmId;
        private final int amfVmId;
        private final int ausfVmId;
        private String hostName = "unknown-host";
        private List<ScalingAction> scalingActions = new ArrayList<>();
        private Map<FaultType, Integer> mitigationCount;
        private Map<Integer, String> labelToDescription;

        private Builder(String name, NetworkOperatingSystem nos,
                        int udmVmId, int amfVmId, int ausfVmId) {
            this.name = name;
            this.nos = nos;
            this.udmVmId = udmVmId;
            this.amfVmId = amfVmId;
            this.ausfVmId = ausfVmId;
        }

        public Builder hostName(String hostName) {
            this.hostName = hostName;
            return this;
        }

        public Builder scalingActions(List<ScalingAction> scalingActions) {
            this.scalingActions = scalingActions != null ? scalingActions : new ArrayList<>();
            return this;
        }

        public Builder mitigationCount(Map<FaultType, Integer> mitigationCount) {
            this.mitigationCount = mitigationCount;
            return this;
        }

        public Builder labelToDescription(Map<Integer, String> labelToDescription) {
            this.labelToDescription = labelToDescription;
            return this;
        }

        public ProactiveMitigator build() {
            if (mitigationCount == null) {
                mitigationCount = new HashMap<>();
                for (FaultType ft : FaultType.values()) {
                    mitigationCount.put(ft, 0);
                }
            }
            if (labelToDescription == null) {
                labelToDescription = new HashMap<>();
                for (FaultType ft : FaultType.values()) {
                    labelToDescription.put(ft.getValue(), ft.name());
                }
            }
            return new ProactiveMitigator(
                    name, nos, udmVmId, amfVmId, ausfVmId,
                    hostName, scalingActions, mitigationCount, labelToDescription
            );
        }
    }
}
