package org.cloudbus.cloudsim.sdn.memory;

public enum FaultType {
    NONE(0),
    BRIDGE_BYTE_STREAK(1),
    INTERFACE_DOWN(2),
    INTERFACE_LOSS_START(3),
    MEMORY_STRESS_START(4),
    VCPU_OVERLOAD_START(5);

    private final int value;

    FaultType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static FaultType fromValue(int value) {
        for (FaultType ft : values()) {
            if (ft.value == value) return ft;
        }
        return NONE;
    }
}
