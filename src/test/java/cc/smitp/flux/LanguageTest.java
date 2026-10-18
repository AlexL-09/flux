package cc.smitp.flux;

import cc.smitp.flux.lang.*;
import cc.smitp.flux.lang.Compiler;
import cc.smitp.flux.graph.*;
import cc.smitp.flux.storage.ProgramRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cc.smitp.flux.lang.Program.*;
import static cc.smitp.flux.graph.NodeDefinitions.Kind;

class LanguageTest {
    static final Compiler.Registry REGISTRY = new Compiler.Registry() {
        public boolean knownItem(String id) { return Set.of("minecraft:carrot", "minecraft:oak_log", "minecraft:birch_log").contains(id); }
        public boolean knownTag(String id) { return id.equals("minecraft:logs"); }
        public Set<String> resolveTag(String id) { return Set.of("minecraft:oak_log", "minecraft:birch_log"); }
    };
    @Test void exampleRoundTripsThroughTextAndGraph() {
        String canonical = cc.smitp.flux.lang.Formatter.format(Parser.parse(ProgramRepository.EXAMPLE));
        assertEquals(canonical, cc.smitp.flux.lang.Formatter.format(Parser.parse(canonical)));
        assertEquals(canonical, cc.smitp.flux.lang.Formatter.format(Graph.from(Parser.parse(canonical)).program()));
        assertTrue(Compiler.compile(Parser.parse(canonical), REGISTRY).valid());
    }
    @Test void allExpressionsAndBranchesRoundTrip() {
        String source = """
            version 1;
            // Keep this annotation
            workflow "Test \\" escapes" {
              on container.opened;
              if !(container.type("minecraft:chest") && player.contains(tag("minecraft:logs"))) || container.contains(item("minecraft:carrot")) {
                withdraw all item("minecraft:carrot");
                if true { notify "yes\\nnewline"; } else { notify "no"; }
              } else { deposit all tag("minecraft:logs"); }
              notify "done";
            }
            """;
        Program p = Parser.parse(source);
        String formatted = cc.smitp.flux.lang.Formatter.format(p);
        assertEquals(formatted, cc.smitp.flux.lang.Formatter.format(Graph.from(p).program()));
        assertEquals(formatted, cc.smitp.flux.lang.Formatter.format(Parser.parse(formatted)));
        assertTrue(formatted.contains("Keep this annotation"));
        assertTrue(Compiler.compile(p, REGISTRY).valid());
    }
    @Test void rejectsInvalidSyntaxWithLocation() {
        Diagnostic.Failure e = assertThrows(Diagnostic.Failure.class, () -> Parser.parse("version 1;\nworkflow \"x\" { on container.opened; deposit potato; }"));
        assertEquals(2, e.diagnostic().span().line()); assertTrue(e.diagnostic().span().column() > 1);
        assertThrows(Diagnostic.Failure.class, () -> Parser.parse("version 2;"));
        assertThrows(Diagnostic.Failure.class, () -> Parser.parse("version 1; workflow \"unterminated"));
        assertThrows(Diagnostic.Failure.class, () -> Parser.parseCondition("!".repeat(600) + "true"));
    }
    @Test void registryAndDuplicateNamesAreValidated() {
        Program p = Parser.parse("version 1; workflow \"x\" { on container.opened; deposit all item(\"minecraft:carrott\"); } workflow \"x\" { on container.opened; }");
        var result = Compiler.compile(p, REGISTRY); assertFalse(result.valid()); assertEquals(2, result.diagnostics().size()); assertTrue(result.plans().isEmpty());
    }
    @Test void graphRejectsCyclesMergesWrongPortsAndDisconnectedNodes() {
        Graph g = new Graph(); var root = g.add(Kind.EVENT, "x", 0, 0); var a = g.add(Kind.NOTIFY, "a", 0, 0); var b = g.add(Kind.NOTIFY, "b", 0, 0);
        assertThrows(Graph.Invalid.class, g::program);
        g.connect(root.id, "next", a.id); g.connect(a.id, "next", b.id);
        assertThrows(Graph.Invalid.class, () -> g.connect(b.id, "next", a.id));
        assertThrows(Graph.Invalid.class, () -> g.connect(root.id, "yes", b.id));
        assertThrows(Graph.Invalid.class, () -> g.connect(b.id, "next", root.id));
        assertEquals(2, g.program().workflows().getFirst().body().size());
        g.nodes.get(root.id).outputs.clear(); g.nodes.get(b.id).outputs.put("next", a.id);
        assertThrows(Graph.Invalid.class, g::program);
    }
    @Test void branchHasTwoSubchainsAndContinuation() {
        Graph g = new Graph(); var root = g.add(Kind.EVENT,"x",0,0); var branch = g.add(Kind.BRANCH,"true",0,0);
        var yes = g.add(Kind.NOTIFY,"yes",0,0); var no = g.add(Kind.NOTIFY,"no",0,0); var after = g.add(Kind.NOTIFY,"after",0,0);
        g.connect(root.id,"next",branch.id); g.connect(branch.id,"yes",yes.id); g.connect(branch.id,"no",no.id); g.connect(branch.id,"next",after.id);
        var body = g.program().workflows().getFirst().body(); assertEquals(2, body.size()); assertEquals("after", ((Notify)body.get(1)).message());
        assertEquals(1, ((Branch)body.getFirst()).yes().size());
    }
    @Test void undoRetainsDisconnectedDraftAndInvalidText() {
        EditorDocument doc = new EditorDocument(ProgramRepository.EXAMPLE); int size = doc.graph.nodes.size();
        doc.checkpoint(); doc.graph.add(Kind.NOTIFY,"disconnected",0,0); doc.undo(); assertEquals(size, doc.graph.nodes.size()); doc.redo(); assertEquals(size+1, doc.graph.nodes.size());
        assertThrows(Graph.Invalid.class, doc::toText);
        EditorDocument invalid = new EditorDocument("version 1; broken"); assertTrue(invalid.textMode); assertThrows(Diagnostic.Failure.class, invalid::toGraph);
    }
}
