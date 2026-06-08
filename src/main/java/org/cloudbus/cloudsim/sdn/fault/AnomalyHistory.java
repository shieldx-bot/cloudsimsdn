package org.cloudbus.cloudsim.sdn.fault;

import java.util.ArrayList;
import java.util.List;

public class AnomalyHistory {
    private final List<AnomalyEvent> events = new ArrayList<>();

    public void add(AnomalyEvent event) {
        events.add(event);
    }

    public List<AnomalyEvent> getAll() {
        return new ArrayList<>(events);
    }

    public void clear() {
        events.clear();
    }
}
