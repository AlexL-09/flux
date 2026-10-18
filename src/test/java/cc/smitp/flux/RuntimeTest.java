package cc.smitp.flux;

import cc.smitp.flux.lang.*;
import cc.smitp.flux.lang.Compiler;
import cc.smitp.flux.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cc.smitp.flux.lang.Program.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTest {
    static final class Fake implements InventoryAdapter {
        final Map<Integer, Stack> slots = new LinkedHashMap<>(); final List<Integer> clicks = new ArrayList<>(); final List<String> messages = new ArrayList<>();
        long revision; boolean valid = true, cursor = true, pickup = true;
        String dimension = "minecraft:overworld";
        Fake() { put(0,"minecraft:carrot",32); put(27,"minecraft:carrot",64); put(28,"minecraft:oak_log",12); }
        void put(int slot, String item, int count) { slots.put(slot,new Stack(slot,item,count,"components",++revision)); }
        public Container container() { return new Container(1,dimension,"minecraft:chest",List.of(new Position(120,64,-30),new Position(121,64,-30))); }
        public boolean valid(long session) { return valid && session == 1; }
        public boolean cursorEmpty() { return cursor; }
        public List<Stack> source(Direction d) { return slots.values().stream().filter(s -> d == Direction.DEPOSIT ? s.slot() >= 27 : s.slot() < 27).toList(); }
        public Stack stack(int slot) { return slots.get(slot); }
        public boolean mayPickup(int slot) { return pickup; }
        public long quickMove(int slot) { clicks.add(slot); return revision; }
        public void notifyLocal(String m) { messages.add(m); }
    }
    private record Setup(Fake fake, TraceBuffer trace, WorkflowRuntime runtime) {}
    private Setup setup(String body) {
        Fake f = new Fake(); TraceBuffer trace = new TraceBuffer(); WorkflowRuntime runtime = new WorkflowRuntime(f,trace,WorkflowRuntime.Limits.DEFAULT);
        var compiled = Compiler.compile(Parser.parse("version 1; workflow \"test\" { on container.opened; " + body + " }"),LanguageTest.REGISTRY);
        assertTrue(compiled.valid()); runtime.opened(compiled.plans(),0); return new Setup(f,trace,runtime);
    }
    @Test void waitsForServerAndIgnoresUnrelatedUpdates() {
        var s = setup("deposit all item(\"minecraft:carrot\"); notify \"done\";");
        s.runtime.tick(1); assertEquals(List.of(27),s.fake.clicks);
        s.fake.put(0,"minecraft:carrot",33); s.runtime.tick(2); assertTrue(s.fake.messages.isEmpty());
        // A local prediction without a new authoritative revision does not confirm the click.
        var old = s.fake.stack(27); s.fake.slots.put(27,new InventoryAdapter.Stack(27,old.item(),0,old.components(),old.revision()));
        s.runtime.tick(3); assertTrue(s.runtime.active());
        s.fake.put(27,"minecraft:carrot",0); s.runtime.tick(4); assertFalse(s.runtime.active()); assertEquals(List.of("done"),s.fake.messages);
    }
    @Test void timeoutDoesNotRetry() {
        var s = setup("deposit all item(\"minecraft:carrot\");"); s.runtime.tick(1); s.runtime.tick(101);
        assertFalse(s.runtime.active()); assertEquals(1,s.fake.clicks.size()); assertTrue(s.trace.runs().getFirst().status().contains("timed out"));
    }
    @Test void partialAndConfirmedNoOpAreReported() {
        var s = setup("deposit all item(\"minecraft:carrot\");"); s.runtime.tick(1); s.fake.put(27,"minecraft:carrot",20); s.runtime.tick(3);
        assertEquals(1,s.fake.clicks.size()); assertTrue(s.trace.runs().getFirst().entries().stream().anyMatch(e -> e.message().contains("44/64")));
        var noOp = setup("withdraw all item(\"minecraft:carrot\");"); noOp.runtime.tick(1); noOp.fake.put(0,"minecraft:carrot",32); noOp.runtime.tick(3);
        assertEquals(1,noOp.fake.clicks.size()); assertFalse(noOp.runtime.active());
    }
    @Test void wrongCoordinatesOrDimensionDoNothing() {
        var s = setup("if container.matches(dimension: \"minecraft:the_nether\", position: [120,64,-30]) { deposit all item(\"minecraft:carrot\"); }");
        s.runtime.tick(1); assertTrue(s.fake.clicks.isEmpty());
        var wrong = setup("if container.matches(dimension: \"minecraft:overworld\", position: [122,64,-30]) { deposit all item(\"minecraft:carrot\"); }");
        wrong.runtime.tick(1); assertTrue(wrong.fake.clicks.isEmpty());
        var otherHalf = setup("if container.matches(dimension: \"minecraft:overworld\", position: [121,64,-30]) { deposit all item(\"minecraft:carrot\"); }");
        otherHalf.runtime.tick(1); assertEquals(List.of(27),otherHalf.fake.clicks);
    }
    @Test void tagsAndWorkflowOrderRespectClickInterval() {
        var s = setup("deposit all tag(\"minecraft:logs\"); deposit all item(\"minecraft:carrot\");");
        s.runtime.tick(1); assertEquals(List.of(28),s.fake.clicks); s.fake.put(28,"minecraft:oak_log",0);
        s.runtime.tick(2); assertEquals(1,s.fake.clicks.size()); s.runtime.tick(3); assertEquals(List.of(28,27),s.fake.clicks);
    }
    @Test void cancellationCursorAndChangedSourceStopSafely() {
        var s = setup("deposit all item(\"minecraft:carrot\");"); s.runtime.tick(1); s.runtime.cancel("Manual input",2); s.fake.put(27,"minecraft:carrot",0); s.runtime.tick(3); assertEquals(1,s.fake.clicks.size());
        var cursor = setup("deposit all item(\"minecraft:carrot\");"); cursor.fake.cursor = false; cursor.runtime.tick(1); assertTrue(cursor.fake.clicks.isEmpty());
        var replaced = setup("deposit all item(\"minecraft:carrot\");"); replaced.runtime.tick(1); replaced.fake.put(27,"minecraft:oak_log",64); replaced.runtime.tick(2); assertTrue(replaced.trace.runs().getFirst().status().contains("unexpectedly"));
    }
    @Test void inflowCannotExtendTransferAndSessionEndCancels() {
        var s = setup("deposit all item(\"minecraft:carrot\");"); s.runtime.tick(1); s.fake.put(27,"minecraft:carrot",0); s.fake.put(29,"minecraft:carrot",64); s.runtime.tick(3);
        assertEquals(List.of(27),s.fake.clicks); assertFalse(s.runtime.active());
        var closed = setup("deposit all item(\"minecraft:carrot\");"); closed.fake.valid = false; closed.runtime.tick(1); assertFalse(closed.runtime.active()); assertTrue(closed.fake.clicks.isEmpty());
    }
    @Test void emptyAndFullInventoriesNeedNoClicks() {
        var empty = setup("deposit all item(\"minecraft:birch_log\");"); empty.runtime.tick(1); assertTrue(empty.fake.clicks.isEmpty()); assertFalse(empty.runtime.active());
        var full = setup("deposit all item(\"minecraft:carrot\");"); full.fake.pickup = false; full.runtime.tick(1); assertTrue(full.fake.clicks.isEmpty()); assertFalse(full.runtime.active());
    }
    @Test void boundedTransitionsAndDeadline() {
        var s = setup("notify \"one\"; notify \"two\";"); s.runtime.limits(new WorkflowRuntime.Limits(2,100,1,100)); s.runtime.tick(1); assertEquals(List.of("one"),s.fake.messages); s.runtime.tick(100); assertFalse(s.runtime.active()); assertEquals(1,s.fake.messages.size());
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRuntime.Limits(0,1,1,1));
    }
}
