CloudSimSDN Anomaly Detection Research - Implementation Log
Generated: 2026-06-07
Feature: ML-ready fault dataset export matching original research format
Target: local clone at /home/ac2006666/cloudsimsdn

================================================================================
SECTION 1: DESIGN OVERVIEW
================================================================================

Goal: emit per-VM fault labels at each monitoring tick using CloudSimSDN
existing monitors (CPU, BW) plus new memory snapshot tracking.

Key Objectives:
1. Map 5 fault types to new detection categories matching your ML labels
2. Output per-interval CSV for ML training: fault_labels.csv
3. Maintain backward compatibility with all existing experiments

================================================================================
SECTION 2: ACTUAL FILES CREATED / MODIFIED
================================================================================

NEW FILES:
-----------
src/main/java/org/cloudbus/cloudsim/sdn/memory/MemoryData.java
  - Immutable data holder: timestamp, usedPercent, totalMemBytes

src/main/java/org/cloudbus/cloudsim/sdn/memory/MemoryMonitor.java
  - Per-VM list of MemoryData snapshots
  - thread-safe record(), getSnapshots(), getLatest()

src/main/java/org/cloudbus/cloudsim/sdn/memory/MemoryTracker.java
  - Static registry: vmId -> MemoryMonitor
  - record(int vmId, ...), getMonitor(int vmId)

src/main/java/org/cloudbus/cloudsim/sdn/memory/MemorySnapshotHelpers.java
  - Calculates usedRamPercentage from Vm + SDNHost (RamProvisioner)

src/main/java/org/cloudbus/cloudsim/sdn/fault/AnomalyType.java
  - Enum mapping fault labels:
    NONE(0), BRIDGE_BYTE_STREAK(1), INTERFACE_DOWN(2),
    INTERFACE_LOSS_START(3), MEMORY_STRESS_START(4),
    VCPU_OVERLOAD_START(5)

src/main/java/org/cloudbus/cloudsim/sdn/fault/AnomalyEvent.java
  - Holds one detected fault: type, vmId, timestamp, metrics map

src/main/java/org/cloudbus/cloudsim/sdn/fault/AnomalyHistory.java
  - Append-only list of AnomalyEvent for post-sim analysis

src/main/java/org/cloudbus/cloudsim/sdn/fault/VmSample.java
  - Single monitoring sample: cpuUtil, memUsedPercent, memTotalBytes,
    bwDataRate, operStatus, zeroOctetCount

src/main/java/org/cloudbus/cloudsim/sdn/fault/AnomalyDetector.java
  - Functional interface: detect(VmSample, int vmId, double timestamp)

src/main/java/org/cloudbus/cloudsim/sdn/fault/MemoryStressDetector.java
  - Emits MEMORY_STRESS_START when memUsedPercent >= 0.90

src/main/java/org/cloudbus/cloudsim/sdn/fault/BridgeByteStreakDetector.java
  - Emits BRIDGE_BYTE_STREAK when zeroOctetCount >= 10

src/main/java/org/cloudbus/cloudsim/sdn/fault/VcpuOverloadDetector.java
  - Emits VCPU_OVERLOAD_START when cpuUtil >= 0.80

src/main/java/org/cloudbus/cloudsim/sdn/fault/InterfaceDetector.java
  - Stateful (downVmId tracking). Emits INTERFACE_DOWN on first 0->1,
    INTERFACE_LOSS_START on subsequent ticks while down

src/main/java/org/cloudbus/cloudsim/sdn/fault/AnomalyAnalyzer.java
  - Chains detectors: vcpu -> mem -> bridge -> interface
  - Returns first matching anomaly per tick

src/main/java/org/cloudbus/cloudsim/sdn/fault/FaultInjectorService.java
  - Singleton usage: instantiated once in NOS
  - Records CSV line per VM per monitoring tick
  - CSV header: timestamp,vmId,vmName,faultType,isAnomaly,cpuUtil,memUsedPercent,bwDataRate,operStatus,zeroOctetCount

MODIFIED FILES:
---------------
src/main/java/org/cloudbus/cloudsim/sdn/virtualcomponents/SDNVm.java
  - Added import: org.cloudbus.cloudsim.sdn.memory.MemoryTracker
  - Added method: recordMemorySnapshot(timestamp, usedPercent, totalMemBytes)
    -> delegates to MemoryTracker.record()

src/main/java/org/cloudbus/cloudsim/sdn/physicalcomponents/SDNHost.java
  - Added imports: MemoryTracker, MemorySnapshotHelpers
  - In updateMonitor(): calls updateMemoryMonitor() before updateVmMonitor()
  - New method: updateMemoryMonitor()
      loops VMs, computes usedPercent via MemorySnapshotHelpers,
      calls vm.recordMemorySnapshot(clock, usedPercent, vm.getRam())

src/main/java/org/cloudbus/cloudsim/sdn/nos/NetworkOperatingSystem.java
  - Added import: org.cloudbus.cloudsim.sdn.fault.FaultInjectorService
  - New field: private final FaultInjectorService faultService = new FaultInjectorService();
  - In MONITOR_UPDATE_UTILIZATION handler: calls updateFaultMonitor(clock) after updateVmMonitor()
  - New method: updateFaultMonitor(logTime)
      loops vmMapId2Vm, collects cpuUtil (time-window avg from MonitoringValues),
      bwRate (time-window avg), mem% from MemoryTracker, then calls
      faultService.record(vmId, vm.getName(), logTime, ...)

NOT YET IMPLEMENTED (future work):
-----------------------------------
- Configuration constants for fault thresholds (currently hard-coded in detectors)
- JSON snapshot exporter (deferred; CSV first)
- Link oper-status tracking (SDNHost has linkToNextHop, but oper-status per Link class not exposed)
- Fault injection scenario loader (virtual topology JSON extension)

================================================================================
SECTION 3: OUTPUT FORMAT
================================================================================

CSV: fault_labels.csv (append, one row per VM per monitoring tick)
Columns:

  timestamp,vmId,vmName,faultType,isAnomaly,cpuUtil,memUsedPercent,bwDataRate,operStatus,zeroOctetCount

Values:
  timestamp   : simulation time (double)
  vmId        : numeric VM id
  vmName      : VM name string (empty if null)
  faultType   : integer code from AnomalyType enum
  isAnomaly   : boolean (true if faultType != 0)
  cpuUtil     : average CPU utilization in window [t-interval, t]
  memUsedPercent : 0..1 fraction of RAM in use
  bwDataRate  : average bytes/sec for VM BW in window
  operStatus  : 0=up, 1=down (always 0 until Link oper-status tracked)
  zeroOctetCount : always 0 until bridge-streak counter added

================================================================================
SECTION 4: DETECTION RULES (implemented)
================================================================================

Memory Stress (4):
  IF sample.memUsedPercent >= 0.90
  THEN emit MEMORY_STRESS_START

Bridge Byte Streak (1):
  IF sample.zeroOctetCount >= 10
  THEN emit BRIDGE_BYTE_STREAK
  NOTE: zeroOctetCount always 0 for now, needs upstream counter

VCPU Overload (5):
  IF sample.cpuUtil >= 0.80
  THEN emit VCPU_OVERLOAD_START
  NOTE: uses total VM CPU util; per-core user/sys split not yet modeled

Interface Down (2) / Loss Start (3):
  IF sample.operStatus == 1 (down)
    AND first down event for this vmId -> INTERFACE_DOWN
    AND subsequent ticks while down -> INTERFACE_LOSS_START
  NOTE: operStatus currently 0 (up) always; needs Link oper-status field

================================================================================
SECTION 5: BUILD STATUS
================================================================================

Compile: BUILD SUCCESS (after edits)
Warnings: Maven dependency systemPath for cloudsim-4.0.jar (pre-existing)

To run:
  mvn compile
  mvn exec:java -Dexec.mainClass="<your experiment main class>" \
    -Dexec.args="" \
    -Dexec.workingDir=./

Output appears under Configuration.workingDirectory + Configuration.experimentName:
  fault_labels.csv

================================================================================
END OF CHANGES LOG - Updated 2026-06-07
================================================================================
