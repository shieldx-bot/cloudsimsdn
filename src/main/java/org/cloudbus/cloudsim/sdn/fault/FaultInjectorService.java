package org.cloudbus.cloudsim.sdn.fault;

import java.util.HashMap;
import java.util.Map;
import org.cloudbus.cloudsim.sdn.LogWriter;

public class FaultInjectorService {
    private final AnomalyAnalyzer analyzer = new AnomalyAnalyzer();
    private final AnomalyHistory history = new AnomalyHistory();
    private final LogWriter csvLogger;

    public FaultInjectorService() {
        this.csvLogger = LogWriter.getLogger("fault_labels.csv");
        initCsvHeader();
    }

    private void initCsvHeader() {
        csvLogger.printLine("timestamp,vmId,vmName,faultType,isAnomaly,cpuUtil,memUsedPercent,bwDataRate,operStatus,zeroOctetCount");
    }

    public void record(
            int vmId,
            String vmName,
            double timestamp,
            double cpuUtil,
            double memUsedPercent,
            long memTotalBytes,
            double bwDataRate,
            int operStatus,
            int zeroOctetCount
    ) {
        VmSample sample = new VmSample(cpuUtil, memUsedPercent, memTotalBytes, bwDataRate, operStatus, zeroOctetCount);
        AnomalyEvent event = analyzer.analyze(sample, vmId, timestamp);
        history.add(event);

        String line = String.format(
                "%.3f,%d,%s,%d,%b,%.4f,%.4f,%.4f,%d,%d",
                timestamp, vmId, vmName != null ? vmName : "",
                event.getType().getCode(), event.isAnomaly(),
                cpuUtil, memUsedPercent, bwDataRate, operStatus, zeroOctetCount
        );
        csvLogger.printLine(line);
    }

    public AnomalyHistory getHistory() {
        return history;
    }
}
