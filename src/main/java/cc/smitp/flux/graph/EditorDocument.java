package cc.smitp.flux.graph;

import cc.smitp.flux.lang.*;
import java.util.*;

/** Undo state includes disconnected graph drafts, layout, and text, not only valid programs. */
public final class EditorDocument {
    private record Snapshot(Graph graph, String text, boolean textMode) {}
    public Graph graph;
    public String text;
    public boolean textMode;
    private final Deque<Snapshot> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    public EditorDocument(String text) {
        this.text = text;
        try { graph = Graph.from(Parser.parse(text)); }
        catch (Diagnostic.Failure e) { graph = new Graph(); textMode = true; }
    }
    private Snapshot snapshot() { return new Snapshot(graph.copy(), text, textMode); }
    public void checkpoint() { undo.addLast(snapshot()); while (undo.size() > 100) undo.removeFirst(); redo.clear(); }
    private void restore(Snapshot s) { graph = s.graph.copy(); text = s.text; textMode = s.textMode; }
    public void undo() { if (!undo.isEmpty()) { redo.addLast(snapshot()); restore(undo.removeLast()); } }
    public void redo() { if (!redo.isEmpty()) { undo.addLast(snapshot()); restore(redo.removeLast()); } }
    public void toText() { String next = cc.smitp.flux.lang.Formatter.format(graph.program()); checkpoint(); text = next; textMode = true; }
    public void toGraph() {
        Graph next = Graph.from(Parser.parse(text)); next.program();
        var layout = new cc.smitp.flux.storage.ProgramRepository.Metadata();
        cc.smitp.flux.storage.ProgramRepository.captureLayout(graph, layout);
        cc.smitp.flux.storage.ProgramRepository.restoreLayout(next, layout);
        checkpoint(); graph = next; textMode = false;
    }
    public String validSource() { return textMode ? text : cc.smitp.flux.lang.Formatter.format(graph.program()); }
}
