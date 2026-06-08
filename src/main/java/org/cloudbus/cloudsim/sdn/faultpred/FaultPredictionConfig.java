package org.cloudbus.cloudsim.sdn.faultpred;

import org.cloudbus.cloudsim.sdn.Configuration;

public final class FaultPredictionConfig {

    private static final int DEFAULT_HORIZON_STEPS = 5;
    private static final String PROP_HORIZON_STEPS = "faultpred.horizon.steps";
    private static final String ENV_HORIZON_STEPS = "FAULTPRED_HORIZON_STEPS";

    private FaultPredictionConfig() {
    }

    public static int getHorizonSteps() {
        String value = firstNonEmpty(System.getProperty(PROP_HORIZON_STEPS), System.getenv(ENV_HORIZON_STEPS));
        if (value == null) {
            return DEFAULT_HORIZON_STEPS;
        }
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(1, parsed);
        } catch (NumberFormatException e) {
            return DEFAULT_HORIZON_STEPS;
        }
    }

    public static double getHorizonSeconds() {
        return getHorizonSteps() * Configuration.monitoringTimeInterval;
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
