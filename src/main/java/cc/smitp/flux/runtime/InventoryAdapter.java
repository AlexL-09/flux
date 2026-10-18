package cc.smitp.flux.runtime;

import cc.smitp.flux.lang.Program.*;
import java.util.List;

/** Snapshots are authoritative server state, never local click prediction. */
public interface InventoryAdapter {
    record Stack(int slot, String item, int count, Object components, long revision) {
        public boolean sameKind(Stack other) { return item.equals(other.item) && components.equals(other.components); }
    }
    record Container(long session, String dimension, String type, List<Position> positions) {
        public Container { positions = List.copyOf(positions); }
    }
    Container container();
    boolean valid(long session);
    boolean cursorEmpty();
    List<Stack> source(Direction direction);
    Stack stack(int slot);
    boolean mayPickup(int slot);
    /** Returns the update revision immediately before the click. */
    long quickMove(int slot);
    void notifyLocal(String message);
}
