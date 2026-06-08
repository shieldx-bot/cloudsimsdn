package org.cloudbus.cloudsim.sdn.faultpred.pipeline;

import org.nd4j.linalg.factory.Nd4j;

public class ModelConfig {

    // Number of threads to use for parallel operations. Default: available processors.
    public static int getThreads() {
        String s = firstNonEmpty(System.getProperty("model.threads"), System.getenv("MODEL_THREADS"));
        if (s != null) {
            try {
                int t = Integer.parseInt(s);
                if (t > 0) return t;
            } catch (NumberFormatException e) {
                // ignore and fallback
            }
        }
        return Runtime.getRuntime().availableProcessors();
    }

    // Whether to attempt using GPU-backed ND4J backend. Default: false.
    // This is only a hint; actual GPU usage depends on the ND4J backend on the classpath.
    public static boolean isGpuEnabled() {
        String s = firstNonEmpty(System.getProperty("model.gpu"), System.getenv("MODEL_GPU"));
        if (s != null) return "true".equalsIgnoreCase(s) || "1".equals(s);
        return false;
    }

    public static String getNd4jBackendName() {
        try {
            return Nd4j.getBackend().getClass().getSimpleName();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    public static boolean isCudaBackendActive() {
        try {
            String backend = Nd4j.getBackend().getClass().getName().toLowerCase();
            return backend.contains("cuda") || backend.contains("gpu");
        } catch (Throwable t) {
            return false;
        }
    }

    public static String runtimeSummary() {
        return String.format("threads=%d, gpuRequested=%s, nd4jBackend=%s",
                getThreads(), isGpuEnabled(), getNd4jBackendName());
    }

    public static void logRuntime(String component) {
        System.out.println(component + ": " + runtimeSummary());
        if (isGpuEnabled() && !isCudaBackendActive()) {
            System.out.println(component + ": GPU was requested, but the active ND4J backend is not CUDA/GPU.");
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
