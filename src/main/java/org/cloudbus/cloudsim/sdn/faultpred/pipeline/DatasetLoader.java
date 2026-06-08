package org.cloudbus.cloudsim.sdn.faultpred.pipeline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class DatasetLoader {

    public static final class PairedData {
        public final double[][] features;
        public final int[] labels;

        public PairedData(double[][] features, int[] labels) {
            this.features = features;
            this.labels = labels;
        }
    }

    public static double[][] loadFeaturesFromDirectory(String directory, String split) throws IOException {
        File[] files = listDatasetFiles(directory, "_" + split + "_data.txt");
        List<double[]> rows = new ArrayList<>();
        int featureCount = -1;
        for (File file : files) {
            double[][] data = loadFeatures(file.getPath());
            for (double[] row : data) {
                if (featureCount < 0) {
                    featureCount = row.length;
                } else if (row.length != featureCount) {
                    throw new IOException("Feature count mismatch in " + file.getPath()
                            + ": expected " + featureCount + " but found " + row.length);
                }
                rows.add(row);
            }
        }
        return rows.toArray(new double[0][]);
    }

    public static int[] loadLabelsFromDirectory(String directory, String split) throws IOException {
        File[] files = listDatasetFiles(directory, "_" + split + "_label.txt");
        List<Integer> labels = new ArrayList<>();
        for (File file : files) {
            int[] data = loadLabels(file.getPath());
            for (int label : data) {
                labels.add(label);
            }
        }
        int[] arr = new int[labels.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = labels.get(i);
        return arr;
    }

    public static String describeDatasetFiles(String directory, String split) throws IOException {
        File[] dataFiles = listDatasetFiles(directory, "_" + split + "_data.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dataFiles.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(dataFiles[i].getName().replace("_" + split + "_data.txt", ""));
        }
        return sb.toString();
    }

    private static File[] listDatasetFiles(String directory, final String suffix) throws IOException {
        File dir = new File(directory);
        File[] files = dir.listFiles((d, name) -> name.endsWith(suffix));
        if (files == null || files.length == 0) {
            throw new IOException("No dataset files ending with " + suffix + " in " + directory);
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    public static double[][] loadFeatures(String filePath) throws IOException {
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split("\\s+");
                double[] row = new double[tokens.length];
                for (int i = 0; i < tokens.length; i++) {
                    row[i] = Double.parseDouble(tokens[i]);
                }
                rows.add(row);
            }
        }
        return rows.toArray(new double[0][]);
    }

    public static int[] loadLabels(String filePath) throws IOException {
        List<Integer> labels = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int label;
                try {
                    label = Integer.parseInt(line);
                } catch (NumberFormatException nfe) {
                    // Skip corrupted label lines (e.g. "1" without newline on a line by itself
                    // or "11" out of class range)
                    continue;
                }
                if (label < 0 || label > 5) {
                    // Out-of-range label; skip silently
                    continue;
                }
                labels.add(label);
            }
        }
        int[] arr = new int[labels.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = labels.get(i);
        return arr;
    }

    public static double[][] subsample(double[][] data, int maxSize) {
        if (data.length <= maxSize) return data;
        int step = Math.max(1, data.length / maxSize);
        List<double[]> out = new ArrayList<>(maxSize);
        for (int i = 0; i < data.length; i += step) {
            out.add(data[i]);
            if (out.size() >= maxSize) break;
        }
        return out.toArray(new double[0][]);
    }

    public static int[] subsample(int[] labels, int maxSize) {
        if (labels.length <= maxSize) return labels;
        int step = Math.max(1, labels.length / maxSize);
        List<Integer> out = new ArrayList<>(maxSize);
        for (int i = 0; i < labels.length; i += step) {
            out.add(labels[i]);
            if (out.size() >= maxSize) break;
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Inspect per-column max and return indices of columns whose max exceeds
     * the threshold. These are typically cumulative counters (e.g. CPU ticks,
     * byte counts) where the raw value carries little signal but the per-interval
     * delta is informative.
     */
    public static int[] detectCounterColumns(double[][] data, double threshold) {
        if (data == null || data.length == 0) return new int[0];
        int n = data[0].length;
        double[] colMax = new double[n];
        for (int j = 0; j < n; j++) colMax[j] = -Double.MAX_VALUE;
        for (double[] row : data) {
            for (int j = 0; j < n; j++) {
                if (row[j] > colMax[j]) colMax[j] = row[j];
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            if (colMax[j] > threshold) out.add(j);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Augment the feature matrix with per-interval deltas of the given columns.
     * <p>For each counter column c, a new column is appended containing
     * {@code data[i][c] - data[i-1][c]} (or 0 at i=0). Cumulative counters
     * like byte totals carry no fault signal, but a sudden drop or stall
     * (a negative or near-zero delta) is highly informative.</p>
     */
    public static double[][] addDeltaFeatures(double[][] data, int[] counterColumns) {
        if (data == null || data.length == 0) return data;
        if (counterColumns == null || counterColumns.length == 0) return data;
        int n = data.length;
        int d = data[0].length;
        int extra = counterColumns.length;
        double[][] out = new double[n][d + extra];
        for (int i = 0; i < n; i++) {
            System.arraycopy(data[i], 0, out[i], 0, d);
            for (int k = 0; k < extra; k++) {
                int c = counterColumns[k];
                if (i == 0) {
                    out[i][d + k] = 0.0;
                } else {
                    out[i][d + k] = data[i][c] - data[i - 1][c];
                }
            }
        }
        return out;
    }

    /**
     * Time-aware balancing that preserves temporal ordering of selected indices.
     * <p>Unlike {@link #balanceForClassification(double[][], int[], double, long)}
     * which shuffles the result via index sort, this method returns indices that
     * remain in ascending (time) order, and additionally takes a sample of
     * NONE-class windows that bracket each fault window so that sliding-window
     * models (LSTM, GRU) retain their temporal structure.</p>
     *
     * <p>Algorithm: for each fault occurrence (consecutive identical fault
     * labels), retain all fault indices. Then take the same number of NONE
     * indices from windows immediately before and after the fault, so the
     * surrounding context is preserved.</p>
     */
    public static PairedData balanceForClassificationTimeAware(double[][] features, int[] labels) {
        return balanceForClassificationTimeAware(features, labels, 1.0);
    }

    public static PairedData balanceForClassificationTimeAware(double[][] features, int[] labels, double noneToFaultRatio) {
        int len = Math.min(features.length, labels.length);
        if (len == 0) {
            return new PairedData(new double[0][], new int[0]);
        }

        // Collect fault indices grouped by contiguous run
        List<List<Integer>> faultRuns = new ArrayList<>();
        List<Integer> currentRun = null;
        for (int i = 0; i < len; i++) {
            int lbl = labels[i];
            if (lbl < 0 || lbl > 5) continue;
            if (lbl != 0) {
                if (currentRun == null || labels[currentRun.get(currentRun.size() - 1)] != lbl) {
                    currentRun = new ArrayList<>();
                    faultRuns.add(currentRun);
                }
                currentRun.add(i);
            } else {
                currentRun = null;
            }
        }

        // Flatten fault indices, keep time order
        List<Integer> faultIdx = new ArrayList<>();
        for (List<Integer> run : faultRuns) faultIdx.addAll(run);

        // For each fault, take equivalent NONE context before AND after (preserves order)
        int targetNone = (int) Math.round(faultIdx.size() * noneToFaultRatio);
        List<Integer> noneIdx = new ArrayList<>();
        // Use even spacing through the data to pick NONE samples around fault events
        if (!faultIdx.isEmpty() && targetNone > 0) {
            int step = Math.max(1, (len - faultIdx.size()) / Math.max(1, targetNone));
            for (int i = 0; i < len && noneIdx.size() < targetNone; i += step) {
                if (labels[i] == 0) noneIdx.add(i);
            }
        }

        // Merge both lists, sort by time (preserves window order for LSTM/GRU)
        List<Integer> selected = new ArrayList<>(faultIdx.size() + noneIdx.size());
        selected.addAll(faultIdx);
        selected.addAll(noneIdx);
        Collections.sort(selected);

        // Deduplicate while preserving order
        List<Integer> dedup = new ArrayList<>(selected.size());
        int last = -1;
        for (int idx : selected) {
            if (idx != last) {
                dedup.add(idx);
                last = idx;
            }
        }

        double[][] sampledFeatures = new double[dedup.size()][];
        int[] sampledLabels = new int[dedup.size()];
        for (int i = 0; i < dedup.size(); i++) {
            int idx = dedup.get(i);
            sampledFeatures[i] = features[idx];
            sampledLabels[i] = labels[idx];
        }
        return new PairedData(sampledFeatures, sampledLabels);
    }

    public static PairedData balanceForClassification(double[][] features, int[] labels) {
        return balanceForClassification(features, labels, 1.0, 42L);
    }

    public static PairedData balanceForClassification(double[][] features, int[] labels, double noneToFaultRatio, long seed) {
        int len = Math.min(features.length, labels.length);
        if (len == 0) {
            return new PairedData(new double[0][], new int[0]);
        }

        List<Integer> noneIdx = new ArrayList<>();
        List<Integer> faultIdx = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Integer>[] perLabel = new ArrayList[6];
        for (int i = 0; i < perLabel.length; i++) {
            perLabel[i] = new ArrayList<>();
        }

        for (int i = 0; i < len; i++) {
            int label = labels[i];
            if (label < 0 || label >= perLabel.length) {
                continue;
            }
            perLabel[label].add(i);
            if (label == 0) {
                noneIdx.add(i);
            } else {
                faultIdx.add(i);
            }
        }

        if (faultIdx.isEmpty() || noneIdx.isEmpty()) {
            return new PairedData(Arrays.copyOf(features, len), Arrays.copyOf(labels, len));
        }

        int targetNone = Math.min(noneIdx.size(), (int) Math.round(faultIdx.size() * noneToFaultRatio));
        if (targetNone <= 0) {
            targetNone = Math.min(noneIdx.size(), faultIdx.size());
        }

        Collections.shuffle(noneIdx, new Random(seed));

        List<Integer> selected = new ArrayList<>(faultIdx.size() + targetNone);
        for (int label = 1; label < perLabel.length; label++) {
            selected.addAll(perLabel[label]);
        }
        selected.addAll(noneIdx.subList(0, targetNone));
        Collections.sort(selected);

        double[][] sampledFeatures = new double[selected.size()][];
        int[] sampledLabels = new int[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            int idx = selected.get(i);
            sampledFeatures[i] = features[idx];
            sampledLabels[i] = labels[idx];
        }
        return new PairedData(sampledFeatures, sampledLabels);
    }

    public static List<TimeSample> toTimeSamples(double[][] features, int[] labels) {
        int len = Math.min(features.length, labels.length);
        List<TimeSample> samples = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            samples.add(new TimeSample(features[i], labels[i]));
        }
        return samples;
    }
}
