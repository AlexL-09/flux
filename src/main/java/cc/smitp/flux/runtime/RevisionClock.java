package cc.smitp.flux.runtime;

import java.util.Arrays;

/** Per-slot vanilla menu state IDs, plus a monotonic local packet revision. */
public final class RevisionClock {
    private final int[] states;
    private final long[] revisions;
    private long sequence;
    public RevisionClock(int slots) { states = new int[slots]; revisions = new long[slots]; Arrays.fill(states, -1); }
    public boolean accept(int slot, int state) {
        if (slot < 0 || slot >= states.length || state < 0 || state > 32767) return false;
        int previous = states[slot], distance = (state - previous) & 32767;
        if (previous >= 0 && (distance == 0 || distance > 16384)) return false;
        states[slot] = state; revisions[slot] = ++sequence; return true;
    }
    public long revision(int slot) { return revisions[slot]; }
    public long sequence() { return sequence; }
}
