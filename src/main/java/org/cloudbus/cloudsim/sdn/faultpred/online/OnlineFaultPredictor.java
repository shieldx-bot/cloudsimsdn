package org.cloudbus.cloudsim.sdn.faultpred.online;

import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.sdn.CloudSimTagsSDN;
import org.cloudbus.cloudsim.sdn.Configuration;
import org.cloudbus.cloudsim.sdn.nos.NetworkOperatingSystem;
import org.cloudbus.cloudsim.sdn.physicalcomponents.SDNHost;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.MinMaxScaler;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.LinearRegressionModel;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.KnnClassifierModel;
import org.cloudbus.cloudsim.sdn.faultpred.pipeline.ModelConfig;
import org.cloudbus.cloudsim.sdn.faultpred.FaultPredictionConfig;
import org.cloudbus.cloudsim.sdn.memory.FaultType;
import org.cloudbus.cloudsim.sdn.fault.FaultInjectorService;
import org.cloudbus.cloudsim.sdn.fault.AnomalyEvent;
import org.cloudbus.cloudsim.sdn.fault.AnomalyHistory;
import org.cloudbus.cloudsim.sdn.faultpred.eval.Metrics;
import org.cloudbus.cloudsim.sdn.faultpred.eval.EvaluationResult;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostModel;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostResult;
import org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostReporter;

public class OnlineFaultPredictor extends SimEntity {

    private final int EXPECTED_VNF_COUNT = 3;
    private final int METRICS_PER_VNF = 11;
    private final int VECTOR_DIM = EXPECTED_VNF_COUNT * METRICS_PER_VNF;

    private final MinMaxScaler scaler;
    private final LinearRegressionModel regModel;
    private final KnnClassifierModel clfModel;
    private final NetworkOperatingSystem nos;
    private final List<SDNHost> vnfHosts;
    private final FaultInjectorService faultService;
    private final List<int[]> predictionHistory;
    private final int horizonSteps;
    private final double horizonSeconds;

    public OnlineFaultPredictor(String name, NetworkOperatingSystem nos, List<SDNHost> vnfHosts) {
        super(name);
        this.nos = nos;
        this.vnfHosts = new ArrayList<>(vnfHosts);
        this.faultService = nos.getFaultService();
        this.predictionHistory = new ArrayList<>();
        this.horizonSteps = FaultPredictionConfig.getHorizonSteps();
        this.horizonSeconds = FaultPredictionConfig.getHorizonSeconds();
        ModelConfig.logRuntime("OnlineFaultPredictor");
        System.out.println("OnlineFaultPredictor: horizon=" + horizonSteps + " step(s), "
                + String.format("%.2f", horizonSeconds) + " second(s)");
        this.scaler = loadScaler(buildModelPath("scaler.model"));
        this.regModel = loadRegression(buildModelPath("reg.model"));
        this.clfModel = loadClassifier(buildModelPath("clf.model"));
    }

    private String buildModelPath(String fileName) {
        String wd = Configuration.workingDirectory;
        if (!wd.endsWith(File.separator) && !wd.endsWith("/")) {
            wd = wd + File.separator;
        }
        return wd + "models" + File.separator + fileName;
    }

    private MinMaxScaler loadScaler(String path) {
        return loadModel(path, MinMaxScaler.class, "scaler");
    }

    private LinearRegressionModel loadRegression(String path) {
        return loadModel(path, LinearRegressionModel.class, "regression");
    }

    private KnnClassifierModel loadClassifier(String path) {
        return loadModel(path, KnnClassifierModel.class, "classifier");
    }

    private <T> T loadModel(String path, Class<T> type, String label) {
        if (path == null || path.isEmpty()) {
            System.out.println(getName() + ": Failed to load " + label + " model: path is empty.");
            return null;
        }
        try (FileInputStream fis = new FileInputStream(path);
             ObjectInputStream ois = new ObjectInputStream(fis)) {
            Object obj = ois.readObject();
            if (type.isInstance(obj)) {
                return type.cast(obj);
            }
            System.out.println(getName() + ": Failed to load " + label + " model from " + path + ": unexpected type " + obj.getClass().getName());
        } catch (Exception e) {
            System.out.println(getName() + ": Failed to load " + label + " model from " + path + ": " + e.getMessage());
        }
        return null;
    }

    @Override
    public void startEntity() {
        send(getId(), Configuration.monitoringTimeInterval, CloudSimTagsSDN.PREDICT_FAULT);
    }

    @Override
    public void processEvent(SimEvent ev) {
        if (ev.getTag() != CloudSimTagsSDN.PREDICT_FAULT) {
            System.out.println(getName() + ": Unknown tag: " + ev.getTag());
            return;
        }

        double now = CloudSim.clock();

        double[] vector = new double[VECTOR_DIM];
        collectMetrics(vector, now);

        if (scaler == null || regModel == null || clfModel == null) {
            System.out.printf("[%.2f] Vector -> PredictedFault=%d (Actual=%d) [models unavailable]%n", now, 0, 0);
            send(getId(), Configuration.monitoringTimeInterval, CloudSimTagsSDN.PREDICT_FAULT);
            return;
        }

        try {
            double[] scaled = scaler.scale(vector);
            double[][] predMetrics = regModel.predict(new double[][]{scaled});
            double[] predictedFuture = predMetrics != null && predMetrics.length > 0 ? predMetrics[0] : vector;
            int[] predLabels = clfModel.predict(new double[][]{predictedFuture});
            int predictedLabel = predLabels != null && predLabels.length > 0 ? predLabels[0] : 0;

            int actualLabel = getActualLabel(now + horizonSeconds);

            System.out.printf("[%.2f] Vector -> PredictedFault=%d (Actual=%d)%n", now, predictedLabel, actualLabel);
            predictionHistory.add(new int[]{(int) now, predictedLabel, actualLabel});

            if (predictedLabel != 0) {
                int vmId = -1;
                if (!vnfHosts.isEmpty() && !vnfHosts.get(0).getVmList().isEmpty()) {
                    vmId = vnfHosts.get(0).getVmList().get(0).getId();
                }
                int[] mitigationData = new int[]{predictedLabel, vmId};
                ProactiveMitigator mitigator = findProactiveMitigator();
                if (mitigator != null) {
                    send(mitigator.getId(), 0, CloudSimTagsSDN.PROACTIVE_MITIGATE, mitigationData);
                }
            }
        } catch (Exception e) {
            System.out.println(getName() + ": Prediction failed at time " + now + ": " + e.getMessage());
        }

        send(getId(), Configuration.monitoringTimeInterval, CloudSimTagsSDN.PREDICT_FAULT);
    }

    private void collectMetrics(double[] vector, double now) {
        for (int h = 0; h < vnfHosts.size(); h++) {
            SDNHost host = vnfHosts.get(h);
            int offset = h * METRICS_PER_VNF;
            if (host == null) {
                fillZero(vector, offset, METRICS_PER_VNF);
                continue;
            }

            double[] hostMetrics = null;
            try {
                hostMetrics = host.collectAllVnfMetrics(now);
            } catch (Exception e) {
                // fallback to zeros if the host cannot provide metrics yet
            }

            if (hostMetrics == null || hostMetrics.length != METRICS_PER_VNF) {
                fillZero(vector, offset, METRICS_PER_VNF);
                continue;
            }

            System.arraycopy(hostMetrics, 0, vector, offset, METRICS_PER_VNF);
        }

        if (vector.length != VECTOR_DIM) {
            System.out.println(getName() + ": Unexpected vector dimension " + vector.length + " at time " + now);
        }
    }

    private void fillZero(double[] vector, int offset, int length) {
        for (int i = 0; i < length; i++) {
            vector[offset + i] = 0.0;
        }
    }

    private int getActualLabel(double targetTime) {
        if (faultService == null) return 0;

        AnomalyHistory history = faultService.getHistory();
        if (history == null) return 0;

        List<AnomalyEvent> events = history.getAll();
        int maxLabel = 0;

        for (AnomalyEvent event : events) {
            double t = event.getTimestamp();
            if (Math.abs(t - targetTime) <= Configuration.monitoringTimeInterval) {
                int label = event.getType().getCode();
                if (label > maxLabel) {
                    maxLabel = label;
                }
            }
        }

        return maxLabel;
    }

    private ProactiveMitigator findProactiveMitigator() {
        int entityId = CloudSim.getEntityId("ProactiveMitigator");
        if (entityId >= 0) {
            try {
                return (ProactiveMitigator) CloudSim.getEntity(entityId);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void shutdownEntity() {
        if (predictionHistory.isEmpty()) {
            System.out.println(getName() + ": No predictions made.");
            return;
        }

        int[] actual = new int[predictionHistory.size()];
        int[] predicted = new int[predictionHistory.size()];
        for (int i = 0; i < predictionHistory.size(); i++) {
            actual[i] = predictionHistory.get(i)[2];
            predicted[i] = predictionHistory.get(i)[1];
        }

        EvaluationResult result = Metrics.computeClassificationMetrics(actual, predicted);

        System.out.println(getName() + ": Final confusion matrix (actual x predicted):");
        System.out.printf("%-12s %6s %6s %6s %6s %6s %6s%n", "", "0", "1", "2", "3", "4", "5");
        for (int i = 0; i < 6; i++) {
            System.out.printf("%-12s %6d %6d %6d %6d %6d %6d%n",
                    FaultType.fromValue(i).name(),
                    result.confusion[i][0],
                    result.confusion[i][1],
                    result.confusion[i][2],
                    result.confusion[i][3],
                    result.confusion[i][4],
                    result.confusion[i][5]);
        }
        System.out.printf("Accuracy: %.4f%n", result.accuracy);
        System.out.printf("Precision: %.4f%n", result.precision);
        System.out.printf("Recall: %.4f%n", result.recall);
        System.out.printf("F1: %.4f%n", result.f1);

        ProactiveMitigator mitigator = findProactiveMitigator();
        java.util.EnumMap<FaultType, Integer> proactiveCounts = new java.util.EnumMap<>(FaultType.class);
        java.util.EnumMap<FaultType, Integer> reactiveCounts = new java.util.EnumMap<>(FaultType.class);
        java.util.List<org.cloudbus.cloudsim.sdn.faultpred.eval.ScalingAction> scalingActions = new java.util.ArrayList<>();
        if (mitigator != null) {
            proactiveCounts.putAll(mitigator.getMitigationCount());
            scalingActions.addAll(mitigator.getScalingActions());
        }
        for (int[] h : predictionHistory) {
            FaultType act = FaultType.fromValue(h[2]);
            reactiveCounts.merge(act, 1, Integer::sum);
        }

        double simDurationHours = predictionHistory.get(predictionHistory.size() - 1)[0] / 3600.0;
        if (simDurationHours <= 0) simDurationHours = CloudSim.clock() / 3600.0;

        double totalUtilSum = 0;
        int utilSamples = 0;
        double totalPeMips = 0;
        double totalRamGb = 0;
        for (SDNHost host : vnfHosts) {
            org.cloudbus.cloudsim.sdn.monitor.MonitoringValues mv = host.getMonitoringValuesHostCPUUtilization();
            if (mv != null) {
                for (double v : mv.getValues()) {
                    totalUtilSum += v;
                }
                utilSamples += mv.getNumberOfPoints();
            }
            totalPeMips += host.getTotalMips();
            for (org.cloudbus.cloudsim.Vm vm : host.getVmList()) {
                totalRamGb += vm.getRam() / 1024.0;
            }
        }

        double agentCpuTimeSec = predictionHistory.size() * 0.050;
        double agentMemGbSec = predictionHistory.size() * 0.0001;
        double agentBwGb = predictionHistory.size() * 0.00001;

        org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostModel extendedModel =
                new org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostModel(
                        simDurationHours,
                        agentCpuTimeSec,
                        agentMemGbSec,
                        agentBwGb,
                        proactiveCounts,
                        reactiveCounts,
                        scalingActions,
                        totalUtilSum,
                        utilSamples,
                        totalPeMips,
                        totalRamGb
                );
        org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostResult extendedResult = extendedModel.compute();
        org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostReporter.print(extendedResult);
        org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostReporter.printScalingDetail(extendedResult);
        org.cloudbus.cloudsim.sdn.faultpred.eval.ExtendedCostReporter.printPerFaultPenaltyDetail(extendedResult);

        FaultCostModelOnline costModel = new FaultCostModelOnline();
        for (int i = 0; i < predictionHistory.size(); i++) {
            int pred = predictionHistory.get(i)[1];
            int act = predictionHistory.get(i)[2];
            if (pred != 0) {
                costModel.recordProactiveResource(FaultType.fromValue(pred), 1000.0, 1000.0);
            }
            if (act != 0) {
                costModel.recordReactiveResource(FaultType.fromValue(act), 1000.0, 1000.0);
            }
        }
        System.out.printf("Resource Saving: %.2f%%%n", costModel.getSavingPercentage());
    }
}
