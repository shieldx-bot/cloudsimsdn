package org.cloudbus.cloudsim.sdn.memory;

import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.provisioners.RamProvisioner;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;

public class MemorySnapshotHelpers {
    public static double getUsedRamPercentage(Vm vm, SDNHost host) {
        RamProvisioner provisioner = host.getRamProvisioner();
        if (provisioner == null) {
            return 0.0;
        }
        long totalRam = vm.getRam();
        long usedRam = totalRam - provisioner.getAvailableRam();
        if (totalRam <= 0) {
            return 0.0;
        }
        return (double) usedRam / totalRam;
    }
}
