package cc.smitp.flux.runtime;

import java.util.*;

public final class TraceBuffer {
    public record Entry(long tick, String message) {}
    public static final class Run {
        public final String workflow;
        private final List<Entry> entries = new ArrayList<>();
        private String status = "running";
        private Run(String workflow) { this.workflow = workflow; }
        public List<Entry> entries() { return List.copyOf(entries); }
        public String status() { return status; }
    }
    private final Deque<Run> runs = new ArrayDeque<>();
    public Run begin(String name, long tick) { Run r = new Run(name); runs.addFirst(r); while (runs.size() > 100) runs.removeLast(); add(r, tick, "container.opened"); return r; }
    public void add(Run r, long tick, String text) { if (r.entries.size() < 512) r.entries.add(new Entry(tick, text)); }
    public void finish(Run r, long tick, String status) { r.status = status; add(r, tick, status); }
    public List<Run> runs() { return List.copyOf(runs); }
}
