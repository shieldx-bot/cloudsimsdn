package org.cloudbus.cloudsim.sdn.fault;

public enum AnomalyType {
    NONE(0),
    BRIDGE_BYTE_STREAK(1),
    INTERFACE_DOWN(2),
    INTERFACE_LOSS_START(3),
    MEMORY_STRESS_START(4),
    VCPU_OVERLOAD_START(5);

    private final int code;

    AnomalyType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static AnomalyType fromCode(int code) {
        for (AnomalyType t : values()) {
            if (t.code == code) return t;
        }
        return NONE;
    }
}
