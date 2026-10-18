package cc.smitp.flux;

import cc.smitp.flux.lang.*;
import cc.smitp.flux.lang.Compiler;
import cc.smitp.flux.graph.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryTest {
    @Test void longSequencesDoNotOverflowGraphTraversal() {
        String text = "version 1; workflow \"long\" { on container.opened; " + "notify \"step\";".repeat(600) + " }";
        var p = Parser.parse(text); assertTrue(Compiler.compile(p,LanguageTest.REGISTRY).valid());
        var g = Graph.from(p); assertEquals(600,g.program().workflows().getFirst().body().size());
    }
    @Test void numericStringsAreNotIntegers() {
        assertThrows(Diagnostic.Failure.class, () -> Parser.parse("version \"1\";"));
        assertThrows(Diagnostic.Failure.class, () -> Parser.parseCondition("container.matches(dimension: \"minecraft:overworld\", position: [\"1\",2,3])"));
    }
    @Test void textRoundTripKeepsNodeLayout() {
        var document = new EditorDocument("version 1; workflow \"x\" { on container.opened; notify \"hello\"; }");
        document.graph.nodes.get(document.graph.roots.getFirst()).x = -150;
        document.toText(); document.toGraph(); assertEquals(-150,document.graph.nodes.get(document.graph.roots.getFirst()).x);
    }
    @Test void manyWorkflowsHaveOneGlobalInstructionBudget() {
        var source = new StringBuilder("version 1;");
        for (int i=0;i<60;i++) source.append("workflow \"w").append(i).append("\" { on container.opened;").append("notify \"a\";".repeat(75)).append('}');
        assertFalse(Compiler.compile(Parser.parse(source.toString()),LanguageTest.REGISTRY).valid());
    }
    @Test void canonicalBooleanParenthesesRemainParsable() {
        String condition = String.join(" && ", java.util.Collections.nCopies(50, "true"));
        var program = Parser.parse("version 1; workflow \"x\" { on container.opened; if " + condition + " { notify \"ok\"; } }");
        assertTrue(Compiler.compile(program, LanguageTest.REGISTRY).valid());
        String canonical = cc.smitp.flux.lang.Formatter.format(program);
        assertEquals(canonical, cc.smitp.flux.lang.Formatter.format(Parser.parse(canonical)));
        assertEquals(canonical, cc.smitp.flux.lang.Formatter.format(Graph.from(program).program()));
    }
}
