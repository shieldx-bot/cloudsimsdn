package org.cloudbus.cloudsim.sdn.example;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.sdn.HostFactory;
import org.cloudbus.cloudsim.sdn.HostFactorySimple;
import org.cloudbus.cloudsim.sdn.SDNBroker;
import org.cloudbus.cloudsim.sdn.workload.Workload;
import org.cloudbus.cloudsim.sdn.monitor.power.PowerUtilizationMaxHostInterface;
import org.cloudbus.cloudsim.sdn.nos.NetworkOperatingSystem;
import org.cloudbus.cloudsim.sdn.nos.NetworkOperatingSystemSimple;
import org.cloudbus.cloudsim.sdn.parsers.PhysicalTopologyParser;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNDatacenter;
import org.cloudbus.cloudsim.sdn.physicalcomponents.switches.Switch;
import org.cloudbus.cloudsim.sdn.policies.selectlink.LinkSelectionPolicy;
import org.cloudbus.cloudsim.sdn.policies.selectlink.LinkSelectionPolicyDestinationAddress;
import org.cloudbus.cloudsim.sdn.policies.vmallocation.VmAllocationPolicyCombinedLeastFullFirst;
import org.cloudbus.cloudsim.sdn.policies.vmallocation.VmAllocationPolicyCombinedMostFullFirst;
import org.cloudbus.cloudsim.sdn.policies.vmallocation.VmAllocationPolicyMipsLeastFullFirst;
import org.cloudbus.cloudsim.sdn.policies.vmallocation.VmAllocationPolicyMipsMostFullFirst;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.faultpred.online.OnlineFaultPredictor;
import org.cloudbus.cloudsim.sdn.faultpred.online.ProactiveMitigator;
import org.cloudbus.cloudsim.sdn.faultpred.online.FaultCostModelOnline;

public class SimpleExampleWithFaultPrediction {
    protected static String physicalTopologyFile = "dataset-energy/energy-physical.json";
    protected static String deploymentFile = "dataset-energy/energy-virtual.json";
    protected static String[] workload_files = {
        "dataset-energy/energy-workload.csv"
    };

    protected static List<String> workloads;

    private static boolean logEnabled = true;

    public interface VmAllocationPolicyFactory {
        public VmAllocationPolicy create(List<? extends Host> list);
    }
    enum VmAllocationPolicyEnum { CombLFF, CombMFF, MipLFF, MipMFF, OverLFF, OverMFF, LFF, MFF, Overbooking }

    private static void printUsage() {
        String runCmd = "java SimpleExampleWithFaultPrediction";
        System.out.format("Usage: %s <LFF|MFF> [physical.json] [virtual.json] [workload1.csv] [workload2.csv] [...]\n", runCmd);
    }

    public static void main(String[] args) {

        String policyName = "LFF";
        workloads = new ArrayList<String>();

        if (args.length >= 1) {
            policyName = args[0];
        }

        VmAllocationPolicyEnum vmAllocPolicy = VmAllocationPolicyEnum.valueOf(policyName);
        if (args.length > 1)
            physicalTopologyFile = args[1];
        if (args.length > 2)
            deploymentFile = args[2];
        if (args.length > 3)
            for (int i = 3; i < args.length; i++) {
                workloads.add(args[i]);
            }
        else
            workloads = (List<String>) Arrays.asList(workload_files);

        printArguments(physicalTopologyFile, deploymentFile, workloads);
        Log.printLine("Starting CloudSim SDN with Fault Prediction...");

        try {
            int num_user = 1;
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;
            CloudSim.init(num_user, calendar, trace_flag);

            VmAllocationPolicyFactory vmAllocationFac = null;
            NetworkOperatingSystem nos = new NetworkOperatingSystemSimple();
            HostFactory hsFac = new HostFactorySimple();
            LinkSelectionPolicy ls = null;
            switch (vmAllocPolicy) {
            case CombMFF:
            case MFF:
                vmAllocationFac = new VmAllocationPolicyFactory() {
                    public VmAllocationPolicy create(List<? extends Host> hostList) {
                        return new VmAllocationPolicyCombinedMostFullFirst(hostList);
                    }
                };
                PhysicalTopologyParser.loadPhysicalTopologySingleDC(physicalTopologyFile, nos, hsFac);
                ls = new LinkSelectionPolicyDestinationAddress();
                break;
            case CombLFF:
            case LFF:
                vmAllocationFac = new VmAllocationPolicyFactory() {
                    public VmAllocationPolicy create(List<? extends Host> hostList) {
                        return new VmAllocationPolicyCombinedLeastFullFirst(hostList);
                    }
                };
                PhysicalTopologyParser.loadPhysicalTopologySingleDC(physicalTopologyFile, nos, hsFac);
                ls = new LinkSelectionPolicyDestinationAddress();
                break;
            case MipMFF:
                vmAllocationFac = new VmAllocationPolicyFactory() {
                    public VmAllocationPolicy create(List<? extends Host> hostList) {
                        return new VmAllocationPolicyMipsMostFullFirst(hostList);
                    }
                };
                PhysicalTopologyParser.loadPhysicalTopologySingleDC(physicalTopologyFile, nos, hsFac);
                ls = new LinkSelectionPolicyDestinationAddress();
                break;
            case MipLFF:
                vmAllocationFac = new VmAllocationPolicyFactory() {
                    public VmAllocationPolicy create(List<? extends Host> hostList) {
                        return new VmAllocationPolicyMipsLeastFullFirst(hostList);
                    }
                };
                PhysicalTopologyParser.loadPhysicalTopologySingleDC(physicalTopologyFile, nos, hsFac);
                ls = new LinkSelectionPolicyDestinationAddress();
                break;
            default:
                System.err.println("Choose proper VM placement policy!");
                printUsage();
                System.exit(1);
            }

            nos.setLinkSelectionPolicy(ls);

            SDNDatacenter datacenter = createSDNDatacenter("Datacenter_0", physicalTopologyFile, nos, vmAllocationFac);

            SDNBroker broker = createBroker();
            int brokerId = broker.getId();

            broker.submitDeployApplication(datacenter, deploymentFile);

            submitWorkloads(broker);

            Map<String, Integer> vmNameToId = NetworkOperatingSystem.getVmNameToIdMap();
            List<SDNHost> vnfHosts = new ArrayList<>();
            int udmVmId = -1, amfVmId = -1, ausfVmId = -1;

            java.util.List<String> desiredNames = new java.util.ArrayList<>(java.util.Arrays.asList("udm", "amf", "ausf"));
            java.util.List<String> foundNames = new java.util.ArrayList<>();

            // First try 5G VNF names
            for (String name : desiredNames) {
                Integer vmId = vmNameToId.get(name);
                if (vmId != null) {
                    SDNHost host = nos.findHost(vmId);
                    if (host != null) {
                        vnfHosts.add(host);
                        foundNames.add(name);
                        switch (name) {
                            case "udm": udmVmId = vmId; break;
                            case "amf": amfVmId = vmId; break;
                            case "ausf": ausfVmId = vmId; break;
                        }
                    }
                }
            }

            // Fallback: if 5G VNFs are not in the scenario, pick first 3 VMs as proxy VNFs
            if (vnfHosts.size() < 3 && !vmNameToId.isEmpty()) {
                System.out.println("Warning: 5G VNFs (udm/amf/ausf) not found. Falling back to first 3 VMs in deployment.");
                for (java.util.Map.Entry<String, Integer> entry : vmNameToId.entrySet()) {
                    if (foundNames.contains(entry.getKey())) continue;
                    Integer vmId = entry.getValue();
                    SDNHost host = nos.findHost(vmId);
                    if (host == null) continue;
                    if (udmVmId < 0) { udmVmId = vmId; foundNames.add("udm"); vnfHosts.add(host); }
                    else if (amfVmId < 0) { amfVmId = vmId; foundNames.add("amf"); vnfHosts.add(host); }
                    else if (ausfVmId < 0) { ausfVmId = vmId; foundNames.add("ausf"); vnfHosts.add(host); }
                    if (vnfHosts.size() >= 3) break;
                }
            }

            for (int i = 0; i < foundNames.size(); i++) {
                System.out.println("Using VNF " + i + " := " + foundNames.get(i));
            }

            if (vnfHosts.isEmpty()) {
                System.out.println("Warning: No VNF hosts found. Fault prediction will not function.");
            }

            OnlineFaultPredictor predictor = new OnlineFaultPredictor("FaultPredictor", nos, vnfHosts);

            ProactiveMitigator.Builder mitigatorBuilder = ProactiveMitigator.builder(
                    "ProactiveMitigator", nos, udmVmId, amfVmId, ausfVmId);
            if (!vnfHosts.isEmpty() && vnfHosts.get(0) != null) {
                mitigatorBuilder.hostName(vnfHosts.get(0).getName());
            }
            ProactiveMitigator mitigator = mitigatorBuilder.build();

            FaultCostModelOnline costModel = new FaultCostModelOnline();

            if (!SimpleExampleWithFaultPrediction.logEnabled)
                Log.disable();

            double finishTime = CloudSim.startSimulation();
            CloudSim.stopSimulation();
            Log.enable();

            broker.printResult();

            Log.printLine(finishTime + ": ========== EXPERIMENT FINISHED ===========");

            List<Workload> wls = broker.getWorkloads();
            if (wls != null)
                LogPrinter.printWorkloadList(wls);

            List<Host> hostList = nos.getHostList();
            List<Switch> switchList = nos.getSwitchList();
            LogPrinter.printEnergyConsumption(hostList, switchList, finishTime);

            Log.printLine("Simultaneously used hosts:" + maxHostHandler.getMaxNumHostsUsed());
            Log.printLine("CloudSim SDN with Fault Prediction finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("Unwanted errors happen");
        }
    }

    public static void submitWorkloads(SDNBroker broker) {
        if (workloads != null) {
            for (String workload : workloads)
                broker.submitRequests(workload);
        }
    }

    public static void printArguments(String physical, String virtual, List<String> workloads) {
        System.out.println("Data center infrastructure (Physical Topology) : " + physical);
        System.out.println("Virtual Machine and Network requests (Virtual Topology) : " + virtual);
        System.out.println("Workloads: ");
        for (String work : workloads)
            System.out.println("  " + work);
    }

    protected static NetworkOperatingSystem nos;
    protected static PowerUtilizationMaxHostInterface maxHostHandler = null;
    protected static SDNDatacenter createSDNDatacenter(String name, String physicalTopology, NetworkOperatingSystem snos, VmAllocationPolicyFactory vmAllocationFactory) {
        nos = snos;
        List<Host> hostList = nos.getHostList();

        String arch = "x86";
        String os = "Linux";
        String vmm = "Xen";

        double time_zone = 10.0;
        double cost = 3.0;
        double costPerMem = 0.05;
        double costPerStorage = 0.001;
        double costPerBw = 0.0;
        LinkedList<Storage> storageList = new LinkedList<Storage>();

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem,
                costPerStorage, costPerBw);

        SDNDatacenter datacenter = null;
        try {
            VmAllocationPolicy vmPolicy = vmAllocationFactory.create(hostList);
            maxHostHandler = (PowerUtilizationMaxHostInterface) vmPolicy;
            datacenter = new SDNDatacenter(name, characteristics, vmPolicy, storageList, 0, nos);

            nos.setDatacenter(datacenter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return datacenter;
    }

    protected static SDNBroker createBroker() {
        SDNBroker broker = null;
        try {
            broker = new SDNBroker("Broker");
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return broker;
    }

    static String WORKLOAD_GROUP_FILENAME = "workload_10sec_100_default.csv";
    static String WORKLOAD_GROUP_FILENAME_BG = "workload_10sec_100.csv";
    static int WORKLOAD_GROUP_NUM = 50;
    static int WORKLOAD_GROUP_PRIORITY = 1;

    public static void submitGroupWorkloads(SDNBroker broker, int workloadsNum, int groupSeperateNum, String filename_suffix_group1, String filename_suffix_group2) {
        for (int set = 0; set < workloadsNum; set++) {
            String filename = filename_suffix_group1;
            if (set >= groupSeperateNum)
                filename = filename_suffix_group2;

            filename = set + "_" + filename;
            broker.submitRequests(filename);
        }
    }
}