package cc.smitp.flux.runtime;

import cc.smitp.flux.lang.Compiler.*;
import cc.smitp.flux.lang.Program.*;
import java.util.*;

/** Tick-driven executor: one click in flight, bounded work, no background game access. */
public final class WorkflowRuntime {
    public record Limits(int clickInterval, int confirmationTimeout, int transitions, int deadline) {
        public static final Limits DEFAULT = new Limits(2, 100, 200, 2000);
        public Limits {
            if (clickInterval < 2 || clickInterval > 40 || confirmationTimeout < 20 || confirmationTimeout > 1200 || transitions < 1 || transitions > 1000 || deadline < 100 || deadline > 12000) throw new IllegalArgumentException("Runtime limits out of bounds");
        }
    }
    private record Candidate(InventoryAdapter.Stack initial) {}
    private final InventoryAdapter inventory;
    private final TraceBuffer trace;
    private Limits limits;
    private final Deque<Plan> queue = new ArrayDeque<>();
    private Plan plan;
    private TraceBuffer.Run run;
    private int pc, moved, expected;
    private long session, startTick, lastClick = Long.MIN_VALUE / 2, pendingTick, pendingRevision;
    private Deque<Candidate> candidates;
    private InventoryAdapter.Stack pending;
    public WorkflowRuntime(InventoryAdapter inventory, TraceBuffer trace, Limits limits) { this.inventory = inventory; this.trace = trace; this.limits = limits; }
    public void limits(Limits limits) { this.limits = limits; }
    public boolean active() { return plan != null || !queue.isEmpty(); }
    public void opened(List<Plan> plans, long tick) {
        cancel("Container changed", tick);
        if (plans.isEmpty()) return;
        session = inventory.container().session(); queue.addAll(plans); next(tick);
    }
    private void next(long tick) {
        plan = queue.poll(); pc = 0; candidates = null; pending = null;
        if (plan != null) { startTick = tick; run = trace.begin(plan.name(), tick); }
    }
    public void cancel(String reason, long tick) {
        if (plan != null) trace.finish(run, tick, "Stopped: " + reason);
        plan = null; queue.clear(); candidates = null; pending = null;
    }
    public void tick(long tick) {
        if (!active()) return;
        if (!inventory.valid(session)) { cancel("Container session ended", tick); return; }
        if (!inventory.cursorEmpty()) { cancel("Cursor is occupied", tick); return; }
        if (tick - startTick >= limits.deadline()) { cancel("Workflow deadline exceeded", tick); return; }
        for (int steps = 0; steps < limits.transitions() && plan != null; steps++) {
            if (pending != null) {
                InventoryAdapter.Stack now = inventory.stack(pending.slot());
                if (now.revision() <= pendingRevision) {
                    if (tick - pendingTick >= limits.confirmationTimeout()) cancel("Server confirmation timed out; no retry", tick);
                    return;
                }
                if (now.count() != 0 && (!pending.sameKind(now) || now.count() > pending.count())) { cancel("Source changed unexpectedly", tick); return; }
                int delta = pending.count() - now.count(); moved += delta;
                trace.add(run, tick, delta == 0 ? "Slot " + pending.slot() + " blocked (confirmed no-op)" : "Slot " + pending.slot() + ": moved " + delta);
                // Each captured source slot is clicked once. A partial quick-move already exhausted destination capacity.
                pending = null;
            }
            if (candidates != null) {
                if (candidates.isEmpty()) {
                    trace.add(run, tick, "Transfer " + (moved == expected ? "complete" : "partial") + ": " + moved + "/" + expected);
                    candidates = null; pc++; continue;
                }
                if (tick - lastClick < limits.clickInterval()) return;
                InventoryAdapter.Stack captured = candidates.removeFirst().initial();
                InventoryAdapter.Stack current = inventory.stack(captured.slot());
                if (!captured.sameKind(current) || current.count() != captured.count()) { cancel("Captured source changed before click", tick); return; }
                if (!inventory.mayPickup(current.slot())) { trace.add(run, tick, "Slot " + current.slot() + " cannot be picked up"); continue; }
                pending = current; pendingTick = lastClick = tick;
                pendingRevision = inventory.quickMove(current.slot()); return;
            }
            if (pc >= plan.instructions().size()) { trace.finish(run, tick, "Completed"); next(tick); continue; }
            switch (plan.instructions().get(pc)) {
                case Move m -> {
                    candidates = new ArrayDeque<>(); moved = 0; expected = 0;
                    for (InventoryAdapter.Stack stack : inventory.source(m.direction())) {
                        if (stack.count() > 0 && m.items().contains(stack.item())) { candidates.add(new Candidate(stack)); expected += stack.count(); }
                    }
                    trace.add(run, tick, m.direction() + ": captured " + expected + " items");
                }
                case Message m -> { inventory.notifyLocal(m.text()); trace.add(run, tick, m.text()); pc++; }
                case Test test -> { boolean match = evaluate(test.condition()); trace.add(run, tick, "Condition: " + match); pc = match ? pc + 1 : test.whenFalse(); }
                case Jump jump -> pc = jump.target();
            }
        }
    }
    private boolean evaluate(Condition c) {
        return switch (c) {
            case Constant k -> k.value();
            case Location l -> l.dimension().equals(inventory.container().dimension()) && inventory.container().positions().contains(l.position());
            case ContainerType t -> t.id().equals(inventory.container().type());
            case Contains v -> inventory.source(v.source() == Source.PLAYER ? Direction.DEPOSIT : Direction.WITHDRAW).stream().anyMatch(s -> s.count() > 0 && plan.selectors().get(v.selector()).contains(s.item()));
            case And a -> evaluate(a.left()) && evaluate(a.right());
            case Or o -> evaluate(o.left()) || evaluate(o.right());
            case Not n -> !evaluate(n.operand());
        };
    }
}
