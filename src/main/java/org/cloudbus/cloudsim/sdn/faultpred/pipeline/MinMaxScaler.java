package org.cloudbus.cloudsim.sdn.faultpred.pipeline;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Feature scaler with automatic detection of cumulative-counter columns
 * (which span many orders of magnitude, e.g. CPU tick counters 0..2.7e9)
 * and apply log1p to compress them before min-max normalization.
 *
 * <p>Without this preprocessing, a row with 81,458 bytes in a counter column
 * would be scaled to ~3e-5 when the column max is 2.7e9, making the signal
 * indistinguishable from zero. After log1p, the value 81,458 becomes ~11.4
 * and the column max becomes ~21.7, so meaningful variation is preserved.</p>
 *
 * <p>The decision to log-transform a column is based on its max value:
 * any column whose maximum exceeds {@link #LOG_THRESHOLD} is treated as a
 * cumulative counter and log1p is applied prior to scaling.</p>
 *
 * <p>Serialized schema v2: adds a {@code boolean[] logTransform} field.
 * v1 objects without this field are rejected by the {@code serialVersionUID}
 * bump to force retraining with the new logic.</p>
 */
public class MinMaxScaler implements Serializable {

    private static final long serialVersionUID = 2L;

    /** Columns whose max exceeds this value are log1p-transformed. */
    public static final double LOG_THRESHOLD = 1.0e4;

    private final double[] min;
    private final double[] max;
    private final boolean[] logTransform;

    /** Auto-detect: any column with max > LOG_THRESHOLD is log1p-transformed. */
    public MinMaxScaler(double[][] data) {
        this(data, detectLogTransform(data));
    }

    /** Explicit log-transform flags (true = apply log1p before min-max). */
    public MinMaxScaler(double[][] data, boolean[] logTransform) {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data is empty");
        }
        int n = data[0].length;
        if (logTransform != null && logTransform.length != n) {
            throw new IllegalArgumentException("logTransform length mismatch");
        }
        this.logTransform = new boolean[n];
        if (logTransform != null) {
            System.arraycopy(logTransform, 0, this.logTransform, 0, n);
        }

        // Step 1: apply log1p to marked columns (in place copy)
        double[][] transformed = new double[data.length][];
        for (int i = 0; i < data.length; i++) {
            double[] src = data[i];
            double[] dst = new double[n];
            for (int j = 0; j < n; j++) {
                double v = src[j];
                if (this.logTransform[j]) {
                    // log1p(0) = 0; safe for negative inputs (clamped)
                    v = (v < 0) ? 0.0 : Math.log1p(v);
                }
                dst[j] = v;
            }
            transformed[i] = dst;
        }

        // Step 2: min-max on transformed data
        this.min = new double[n];
        this.max = new double[n];
        for (int j = 0; j < n; j++) {
            min[j] = Double.MAX_VALUE;
            max[j] = -Double.MAX_VALUE;
        }
        for (double[] row : transformed) {
            for (int j = 0; j < n; j++) {
                if (row[j] < min[j]) min[j] = row[j];
                if (row[j] > max[j]) max[j] = row[j];
            }
        }
    }

    /**
     * Inspect per-column max and mark any column whose max > LOG_THRESHOLD
     * as a log-transform candidate.
     */
    public static boolean[] detectLogTransform(double[][] data) {
        if (data == null || data.length == 0) return new boolean[0];
        int n = data[0].length;
        boolean[] flags = new boolean[n];
        double[] colMax = new double[n];
        for (int j = 0; j < n; j++) colMax[j] = -Double.MAX_VALUE;

        for (double[] row : data) {
            for (int j = 0; j < n; j++) {
                if (row[j] > colMax[j]) colMax[j] = row[j];
            }
        }
        for (int j = 0; j < n; j++) {
            if (colMax[j] > LOG_THRESHOLD) flags[j] = true;
        }
        return flags;
    }

    public double[] scale(double[] row) {
        double[] out = new double[row.length];
        for (int j = 0; j < row.length; j++) {
            double v = row[j];
            if (logTransform[j]) {
                v = (v < 0) ? 0.0 : Math.log1p(v);
            }
            double range = max[j] - min[j];
            out[j] = range == 0 ? 0.0 : (v - min[j]) / range;
        }
        return out;
    }

    public double[][] scale(double[][] data) {
        double[][] out = new double[data.length][];
        for (int i = 0; i < data.length; i++) out[i] = scale(data[i]);
        return out;
    }

    public double[] inverseScale(double[] row) {
        double[] out = new double[row.length];
        for (int j = 0; j < row.length; j++) {
            double range = max[j] - min[j];
            double v = range == 0 ? min[j] : row[j] * range + min[j];
            if (logTransform[j]) {
                v = Math.expm1(v);
                if (v < 0) v = 0.0;
            }
            out[j] = v;
        }
        return out;
    }

    public double[][] inverseScale(double[][] data) {
        double[][] out = new double[data.length][];
        for (int i = 0; i < data.length; i++) out[i] = inverseScale(data[i]);
        return out;
    }

    /** Read-only view of the log-transform flag vector. */
    public boolean[] getLogTransform() {
        return logTransform.clone();
    }

    public void save(ObjectOutputStream out) throws java.io.IOException {
        out.writeObject(this);
    }

    public static MinMaxScaler load(ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        return (MinMaxScaler) in.readObject();
    }
}
