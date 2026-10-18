package cc.smitp.client.ui;

import cc.smitp.client.FluxController;
import cc.smitp.flux.graph.*;
import cc.smitp.flux.lang.*;
import cc.smitp.flux.storage.ProgramRepository;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.*;
import static cc.smitp.flux.graph.NodeDefinitions.Kind;

/** Native Minecraft widgets surround a custom, typed, structured circuit canvas. */
public final class FluxScreen extends Screen {
    private static final int BG = 0xFF11171F, PANEL = 0xFF19222E, GRID = 0xFF222E3B, INK = 0xFFE5EBF1, MUTED = 0xFF9AAABD, ACCENT = 0xFF61C4CF;
    private final FluxController flux;
    private final EditorDocument document;
    private String selected, errorNode, linkFrom, linkPort, dragging;
    private String message = "Connect an output to a node's input. F9 stops automation.";
    private String filter = "";
    private EditBox search;
    private MultiLineEditBox textEditor, propertyEditor;
    private double panX = 25, panY = 28, zoom = 0.8;
    private int left, right, top = 69, bottom, traceOffset, inspectorOffset, inspectorMax, paletteOffset, paletteMax;
    private boolean traceTab, panning, changingWidgets, creatingDrag, initialFit = true;
    private final Map<String, String> propertyDrafts = new HashMap<>();
    private String propertyOwner;
    private long lastTextEdit;
    public FluxScreen(FluxController flux) {
        super(Component.literal("Flux · Inventory automation")); this.flux = flux;
        if (flux.editorDocument == null) {
            flux.editorDocument = new EditorDocument(flux.draft);
            ProgramRepository.restoreLayout(flux.editorDocument.graph, flux.metadata);
        }
        document = flux.editorDocument;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override protected void init() {
        if (propertyEditor != null && propertyOwner != null) propertyDrafts.put(propertyOwner, propertyEditor.getValue());
        changingWidgets = true; clearWidgets();
        left = width < 560 ? 108 : 148; right = width < 560 ? 138 : 188; bottom = height - 28;
        button("Circuit", 10, 35, 59, () -> switchTab(false));
        button("Text", 73, 35, 49, () -> switchTab(true));
        button("Trace", 126, 35, 49, () -> { traceTab = true; init(); });
        if (width >= 405) {
            button("Undo", 183, 35, 43, () -> { document.undo(); propertyDrafts.clear(); propertyEditor = null; selected = null; init(); });
            button("Redo", 230, 35, 43, () -> { document.redo(); propertyDrafts.clear(); propertyEditor = null; selected = null; init(); });
        }
        button("Apply", width - 116, 35, 52, this::apply);
        button("Stop", width - 60, 35, 50, flux::stop);
        textEditor = null; propertyEditor = null;
        if (traceTab) {
            button("Clear view", 12, 74, 80, () -> { traceOffset = 0; });
            button("Refresh", 96, 74, 70, () -> init());
        } else if (document.textMode) {
            textEditor = MultiLineEditBox.builder().setX(12).setY(top + 4).setPlaceholder(Component.literal("version 1;\nworkflow ...")).build(font, width - 24, Math.max(30, bottom - top - 8), Component.literal("Flux source code"));
            textEditor.setCharacterLimit(262144); textEditor.setValue(document.text);
            textEditor.setValueListener(value -> {
                if (changingWidgets) return;
                long now = System.currentTimeMillis(); if (now - lastTextEdit > 600) document.checkpoint();
                lastTextEdit = now; document.text = value;
            });
            addRenderableWidget(textEditor);
        } else {
            search = new EditBox(font, 9, top + 22, left - 18, 18, Component.literal("Search nodes"));
            search.setValue(filter); search.setHint(Component.literal("Search nodes"));
            search.setResponder(value -> { if (!changingWidgets) { filter = value; rebuildPalette(); } });
            addRenderableWidget(search); buildPalette(); buildSidebar(); buildInspector();
            clipSidebar();
            if (initialFit) { fit(); initialFit = false; }
        }
        // 26.2 rejects zero-area scissors: never submit widgets wholly outside the viewport.
        for (var child : children()) if (child instanceof AbstractWidget w) {
            if (w.getY() >= top && w.getY() + w.getHeight() > bottom) w.visible = false;
        }
        changingWidgets = false;
    }
    private final List<Button> paletteButtons = new ArrayList<>();
    private void rebuildPalette() { paletteButtons.forEach(this::removeWidget); paletteButtons.clear(); buildPalette(); clipSidebar(); }
    private void buildPalette() {
        paletteButtons.clear(); int y = top + 48 - paletteOffset;
        for (var definition : NodeDefinitions.ALL) {
            if (!definition.label().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
            Kind kind = definition.kind();
            Button b = button(definition.label(), 9, y, left - 18, () -> addNode(kind)); paletteButtons.add(b); y += 24;
        }
    }
    private void buildSidebar() {
        button("Fit canvas", 9, top + 181 - paletteOffset, left - 18, this::fit);
        button("New workflow", 9, top + 207 - paletteOffset, left - 18, () -> addNode(Kind.EVENT));
        int row = 0;
        for (String root : document.graph.roots) {
            var node = document.graph.nodes.get(root); String label = (flux.enabled(node.value) ? "● " : "○ ") + node.value;
            button(shorten(label, left - 27), 9, top + 263 - paletteOffset + row++ * 23, left - 18, () -> { selected = root; center(node); init(); });
        }
    }
    private void clipSidebar() {
        paletteMax = Math.max(0, top + 288 + document.graph.roots.size() * 23 - bottom);
        for (var child : children()) if (child instanceof Button w && w.getX() < left && w.getY() != 35) {
            w.visible = w.getY() >= top + 44 && w.getY() + w.getHeight() <= bottom;
        }
    }
    private void buildInspector() {
        int x = width - right + 10, w = right - 20;
        int contentTop = top - inspectorOffset;
        var node = document.graph.nodes.get(selected);
        if (node == null) return;
        int propertyHeight = Math.max(40, Math.min(110, bottom - top - 222));
        propertyEditor = MultiLineEditBox.builder().setX(x).setY(contentTop + 47).build(font, w, propertyHeight, Component.literal(NodeDefinitions.get(node.kind).propertyHint()));
        propertyEditor.setCharacterLimit(8192); propertyOwner = node.id; propertyEditor.setValue(propertyDrafts.getOrDefault(node.id, node.value)); addRenderableWidget(propertyEditor);
        int y = contentTop + 52 + propertyHeight;
        button("Set property", x, y, w, () -> {
            document.checkpoint(); node.value = propertyEditor.getValue(); propertyDrafts.remove(node.id); message = "Property updated. Apply to activate."; errorNode = null;
            if (node.kind == Kind.BRANCH) try { Parser.parseCondition(node.value); } catch (Diagnostic.Failure e) { message = e.diagnostic().toString(); errorNode = node.id; }
            init();
        }); y += 24;
        if (node.kind == Kind.DEPOSIT || node.kind == Kind.WITHDRAW) {
            button(node.tag ? "Selector: tag" : "Selector: item", x, y, w, () -> { document.checkpoint(); node.tag = !node.tag; init(); }); y += 24;
        }
        if (node.kind == Kind.BRANCH) { button("Capture container", x, y, w, () -> capture(node)); y += 24; }
        if (node.kind == Kind.EVENT) {
            button(flux.enabled(node.value) ? "Disable in this profile" : "Enable in this profile", x, y, w, () -> { message = flux.toggle(node.value); init(); }); y += 24;
            button("Run earlier", x, y, w / 2 - 2, () -> reorder(node.id, -1));
            button("Run later", x + w / 2 + 2, y, w / 2 - 2, () -> reorder(node.id, 1)); y += 24;
        }
        button("Delete node", x, y, w, () -> { document.checkpoint(); document.graph.remove(node.id); selected = null; message = "Node deleted; reconnect any detached chain."; init(); }); y += 28;
        for (String port : NodeDefinitions.get(node.kind).outputs()) {
            String p = port;
            button("Connect " + port, x, y, w / 2 - 2, () -> { linkFrom = node.id; linkPort = p; message = "Select a target node input (or select a node and press Enter)."; });
            button("Unlink " + port, x + w / 2 + 2, y, w / 2 - 2, () -> { document.checkpoint(); node.outputs.remove(p); init(); }); y += 22;
        }
        inspectorMax = Math.max(0, y + inspectorOffset - bottom + 8);
        for (var child : children()) if (child instanceof AbstractWidget widget && widget.getX() >= width - right && widget.getY() != 35) {
            widget.visible = widget.getY() >= top + 42 && widget.getY() + widget.getHeight() <= bottom;
        }
    }
    private void reorder(String id, int delta) {
        int i = document.graph.roots.indexOf(id), to = i + delta;
        if (to >= 0 && to < document.graph.roots.size()) { document.checkpoint(); Collections.swap(document.graph.roots, i, to); init(); }
    }
    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, Math.max(20, w), 20).build());
    }
    private void addNode(Kind kind) {
        document.checkpoint();
        String value = switch (kind) {
            case EVENT -> { int i = document.graph.roots.size() + 1; String name; do { name = "Workflow " + i++; } while (hasName(name)); yield name; }
            case BRANCH -> "true";
            case DEPOSIT, WITHDRAW -> "minecraft:carrot";
            case NOTIFY -> "Workflow finished";
        };
        var node = document.graph.add(kind, value, (Math.max(40, (width - left - right) / 2.0 - 80) - panX) / zoom, (60 - panY) / zoom);
        selected = node.id; inspectorOffset = 0; dragging = node.id; creatingDrag = true; message = "Drag the node onto the canvas, then connect its ports."; init();
    }
    private boolean hasName(String name) { return document.graph.roots.stream().anyMatch(id -> document.graph.nodes.get(id).value.equals(name)); }
    private void capture(Graph.Node node) {
        var container = flux.tracker.last();
        if (container == null || container.positions().isEmpty()) { message = "Open a supported container first, then return to Flux."; return; }
        document.checkpoint(); node.value = cc.smitp.flux.lang.Formatter.condition(new Program.Location(container.dimension(), container.positions().getFirst()));
        propertyDrafts.remove(node.id); propertyEditor = null;
        message = "Captured " + container.dimension() + " " + container.positions().getFirst(); init();
    }
    private void switchTab(boolean text) {
        try {
            if (text && !document.textMode) document.toText();
            else if (!text && document.textMode) { document.toGraph(); selected = null; fit(); }
            traceTab = false; message = "Draft · Apply validates, saves, and activates."; init();
        } catch (Diagnostic.Failure e) { message = e.diagnostic().toString(); }
        catch (Graph.Invalid e) { message = e.getMessage(); errorNode = e.node; }
    }
    private void apply() {
        try {
            String text = document.validSource();
            Program p = Parser.parse(text); var result = flux.validate(p);
            if (!result.valid()) { message = result.diagnostics().getFirst().toString(); markInvalidNode(); return; }
            String error = flux.apply(text, document.textMode ? null : document.graph);
            if (error != null) { message = error; return; }
            document.text = flux.source; errorNode = null; message = flux.status + " · Enable each workflow for this profile."; init();
        } catch (Graph.Invalid e) { message = e.getMessage(); errorNode = e.node; }
        catch (Diagnostic.Failure e) { message = e.diagnostic().toString(); }
    }
    private void markInvalidNode() {
        if (document.textMode) return;
        for (var n : document.graph.nodes.values()) {
            Program.Statement statement = switch (n.kind) {
                case DEPOSIT, WITHDRAW -> new Program.Transfer(n.kind == Kind.DEPOSIT ? Program.Direction.DEPOSIT : Program.Direction.WITHDRAW, new Program.Selector(n.tag, n.value), n.span);
                case BRANCH -> new Program.Branch(Parser.parseCondition(n.value), List.of(), List.of(), n.span);
                default -> new Program.Notify("", n.span);
            };
            if (!flux.validate(new Program(1, List.of(new Program.Workflow("validation", List.of(statement), n.span)), List.of())).valid()) { errorNode = n.id; selected = n.id; init(); return; }
        }
    }
    private String shorten(String value, int maxWidth) { return font.width(value) <= maxWidth ? value : font.plainSubstrByWidth(value, Math.max(1, maxWidth - 9)) + "…"; }
    private void center(Graph.Node n) { panX = (width - left - right) / 2.0 - (n.x + 95) * zoom; panY = 50 - n.y * zoom; }
    private void fit() {
        if (document.graph.nodes.isEmpty()) { panX = 25; panY = 28; zoom = .8; return; }
        double minX = document.graph.nodes.values().stream().mapToDouble(n -> n.x).min().orElse(0), minY = document.graph.nodes.values().stream().mapToDouble(n -> n.y).min().orElse(0);
        double maxX = document.graph.nodes.values().stream().mapToDouble(n -> n.x + 190).max().orElse(190), maxY = document.graph.nodes.values().stream().mapToDouble(n -> n.y + 118).max().orElse(118);
        zoom = Math.max(.25, Math.min(1, Math.min((width - left - right - 30) / (maxX - minX), (bottom - top - 30) / (maxY - minY))));
        panX = 15 - minX * zoom; panY = 15 - minY * zoom;
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) { g.fill(0, 0, width, height, BG); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        extractBackground(g, mx, my, delta);
        g.text(font, "F L U X", 12, 12, ACCENT, false);
        g.text(font, "INVENTORY AUTOMATION", 83, 13, MUTED, false);
        if (width > 530) g.text(font, shorten(flux.profileLabel(), 165), width - 178, 13, MUTED, false);
        g.horizontalLine(0, width, 62, GRID);
        if (traceTab) renderTrace(g);
        else if (!document.textMode) {
            g.fill(0, top, left, bottom, PANEL); g.fill(width - right, top, width, bottom, PANEL);
            g.text(font, "NODES · scroll", 10, top + 7, MUTED, false);
            g.text(font, inspectorMax > 0 ? "INSPECTOR · scroll" : "INSPECTOR", width - right + 10, top + 7, MUTED, false);
            var node = document.graph.nodes.get(selected);
            if (node != null) g.text(font, shorten(NodeDefinitions.get(node.kind).propertyHint(), right - 20), width - right + 10, top + 29, INK, false);
            else g.textWithWordWrap(font, Component.literal("Select a node to edit its properties.\n\nDrag ports to connect. Middle-drag to pan. Scroll to zoom.\n\nBranches run Yes or No, then Next."), width - right + 10, top + 31, right - 20, MUTED);
            renderCanvas(g, mx, my);
        }
        g.fill(0, bottom + 2, width, height, PANEL);
        g.text(font, shorten(message, width - 20), 10, height - 17, errorNode == null ? MUTED : 0xFFFF918A, false);
        super.extractRenderState(g, mx, my, delta);
    }
    private void renderCanvas(GuiGraphicsExtractor g, int mx, int my) {
        g.enableScissor(left, top, width - right, bottom);
        int step = Math.max(8, (int) (24 * zoom));
        for (int x = left + (int) (panX % step); x < width - right; x += step)
            for (int y = top + (int) (panY % step); y < bottom; y += step) g.fill(x, y, x + 1, y + 1, GRID);
        g.pose().pushMatrix(); g.pose().translate((float) (left + panX), (float) (top + panY)); g.pose().scale((float) zoom, (float) zoom);
        for (var n : document.graph.nodes.values()) {
            var ports = NodeDefinitions.get(n.kind).outputs();
            for (var edge : n.outputs.entrySet()) {
                var target = document.graph.nodes.get(edge.getValue()); if (target == null) continue;
                wire(g, n.x + 190, n.y + 50 + ports.indexOf(edge.getKey()) * 21, target.x, target.y + 50, NodeDefinitions.get(n.kind).color());
            }
        }
        if (linkFrom != null && document.graph.nodes.containsKey(linkFrom)) {
            var n = document.graph.nodes.get(linkFrom); int index = NodeDefinitions.get(n.kind).outputs().indexOf(linkPort);
            wire(g, n.x + 190, n.y + 50 + index * 21, worldX(mx), worldY(my), ACCENT);
        }
        for (var n : document.graph.nodes.values()) {
            if (left + panX + (n.x + 195) * zoom < left || left + panX + n.x * zoom > width - right || top + panY + (n.y + 118) * zoom < top || top + panY + n.y * zoom > bottom) continue;
            int x = (int) n.x, y = (int) n.y, h = n.kind == Kind.BRANCH ? 112 : 80; var definition = NodeDefinitions.get(n.kind);
            g.fill(x + 3, y + 4, x + 193, y + h + 4, 0x60000000);
            g.fill(x, y, x + 190, y + h, PANEL); g.fill(x, y, x + 190, y + 25, 0xFF263444);
            g.fill(x, y, x + 3, y + h, definition.color());
            if (n.id.equals(selected) || n.id.equals(errorNode)) g.outline(x - 1, y - 1, 192, h + 2, n.id.equals(errorNode) ? 0xFFFF918A : ACCENT);
            if (n.kind != Kind.EVENT) { g.fill(x - 4, y + 46, x + 4, y + 54, ACCENT); }
            int i = 0;
            for (String port : definition.outputs()) { int py = y + 50 + i++ * 21; g.fill(x + 186, py - 4, x + 194, py + 4, definition.color()); }
        }
        g.pose().popMatrix();
        // Keep labels at native pixel size when zoomed out; fractional font scaling aliases badly.
        for (var n : document.graph.nodes.values()) {
            int x = (int) (left + panX + n.x * zoom), y = (int) (top + panY + n.y * zoom);
            if (zoom >= .5 && x + 190 * zoom >= left && x < width - right && y + 118 * zoom >= top && y < bottom) {
                int labelWidth = (int) (166 * zoom);
                g.text(font, shorten(NodeDefinitions.get(n.kind).label(), labelWidth), x + 7, y + 5, INK, false);
                g.text(font, shorten((n.tag ? "#" : "") + n.value.replace('\n', ' '), labelWidth), x + 7, y + (int) (32 * zoom), MUTED, false);
                if (n.kind != Kind.EVENT) g.text(font, "in", x + 7, y + (int) (47 * zoom), MUTED, false);
                int portIndex = 0;
                for (String port : NodeDefinitions.get(n.kind).outputs()) {
                    g.text(font, port, x + (int) (180 * zoom) - font.width(port), y + (int) ((50 + portIndex++ * 21) * zoom) - 3, MUTED, false);
                }
            }
        }
        g.disableScissor();
        if (canvas(mx, my)) {
            var hovered = hit(worldX(mx), worldY(my));
            if (hovered != null && dragging == null && linkFrom == null) g.setTooltipForNextFrame(font, Component.literal(NodeDefinitions.get(hovered.kind).label() + "\n" + hovered.value), mx, my);
        }
    }
    private void wire(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, int color) {
        double bend = Math.max(45, Math.abs(x2 - x1) * .45), px = x1, py = y1;
        double minX = Math.min(x1, x2 - bend), maxX = Math.max(x2, x1 + bend);
        if (maxX < worldX(left) || minX > worldX(width - right) || Math.max(y1, y2) < worldY(top) || Math.min(y1, y2) > worldY(bottom)) return;
        for (int i = 1; i <= 40; i++) {
            double t = i / 40.0, u = 1 - t;
            double x = u*u*u*x1 + 3*u*u*t*(x1+bend) + 3*u*t*t*(x2-bend) + t*t*t*x2;
            double y = u*u*u*y1 + 3*u*u*t*y1 + 3*u*t*t*y2 + t*t*t*y2;
            double length = Math.hypot(x - px, y - py);
            if (length > .01) {
                g.pose().pushMatrix(); g.pose().translate((float) px, (float) py); g.pose().rotate((float) Math.atan2(y - py, x - px));
                g.fill(0, 0, (int) Math.ceil(length) + 1, 2, color); g.pose().popMatrix();
            }
            px = x; py = y;
        }
    }
    private void renderTrace(GuiGraphicsExtractor g) {
        g.enableScissor(10, top + 32, width - 10, bottom);
        int y = top + 39 - traceOffset;
        g.text(font, flux.diagnostics(), 14, y, ACCENT, false); y += 24;
        if (flux.trace.runs().isEmpty()) g.text(font, "No runs yet. Enable a workflow, then open a matching container.", 14, y, MUTED, false);
        for (var run : flux.trace.runs()) {
            g.text(font, run.workflow + " · " + run.status(), 14, y, INK, false); y += 17;
            for (var entry : run.entries()) { if (y > top + 25 && y < bottom) g.text(font, shorten("  " + entry.tick() + "  " + entry.message(), width - 35), 14, y, MUTED, false); y += 13; }
            y += 16;
        }
        g.disableScissor();
    }
    private boolean canvas(double x, double y) { return !traceTab && !document.textMode && x >= left && x < width - right && y >= top && y < bottom; }
    private double worldX(double x) { return (x - left - panX) / zoom; }
    private double worldY(double y) { return (y - top - panY) / zoom; }
    private Graph.Node hit(double x, double y) {
        List<Graph.Node> nodes = new ArrayList<>(document.graph.nodes.values()); Collections.reverse(nodes);
        return nodes.stream().filter(n -> x >= n.x - 9 && x <= n.x + 199 && y >= n.y && y <= n.y + (n.kind == Kind.BRANCH ? 112 : 80)).findFirst().orElse(null);
    }
    private void finishLink(Graph.Node target) {
        if (target == null || linkFrom == null) return;
        try { document.checkpoint(); document.graph.connect(linkFrom, linkPort, target.id); message = "Connected " + linkPort + "."; errorNode = null; }
        catch (Graph.Invalid e) { message = e.getMessage(); errorNode = e.node; }
        linkFrom = null; linkPort = null;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!canvas(event.x(), event.y())) return super.mouseClicked(event, doubleClick);
        if (event.button() == 2 || event.button() == 1) { panning = true; return true; }
        if (event.button() != 0) return false;
        double x = worldX(event.x()), y = worldY(event.y()); var n = hit(x, y);
        if (n == null) { selected = null; linkFrom = null; init(); return true; }
        if (linkFrom != null && x < n.x + 20 && Math.abs(y - n.y - 50) < 13) { finishLink(n); return true; }
        var ports = NodeDefinitions.get(n.kind).outputs();
        for (int i = 0; i < ports.size(); i++) if (Math.abs(x - n.x - 190) < 13 && Math.abs(y - n.y - 50 - i * 21) < 11) {
            linkFrom = n.id; linkPort = ports.get(i); selected = n.id; message = "Drag to an input, or click an input to connect."; init(); return true;
        }
        selected = n.id; inspectorOffset = 0; document.checkpoint(); dragging = n.id; init(); return true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (panning) { panX += dx; panY += dy; return true; }
        if (dragging != null && !document.textMode && !traceTab) { var n = document.graph.nodes.get(dragging); if (n != null) { if (creatingDrag) { n.x = worldX(event.x()) - 95; n.y = worldY(event.y()) - 12; } else { n.x += dx / zoom; n.y += dy / zoom; } } return true; }
        if (linkFrom != null) return true;
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (linkFrom != null && canvas(event.x(), event.y())) { var n = hit(worldX(event.x()), worldY(event.y())); if (n != null && !n.id.equals(linkFrom)) finishLink(n); }
        dragging = null; creatingDrag = false; panning = false; return super.mouseReleased(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (traceTab) { traceOffset = Math.max(0, traceOffset - (int) (sy * 30)); return true; }
        if (!document.textMode && x >= width - right && y >= top && y < bottom && !(propertyEditor != null && propertyEditor.isMouseOver(x, y))) {
            inspectorOffset = Math.max(0, Math.min(inspectorMax, inspectorOffset - (int) (sy * 24))); init(); return true;
        }
        if (!document.textMode && x < left && y >= top + 42 && y < bottom) {
            paletteOffset = Math.max(0, Math.min(paletteMax, paletteOffset - (int) (sy * 24))); init(); return true;
        }
        if (!canvas(x, y)) return super.mouseScrolled(x, y, sx, sy);
        double wx = worldX(x), wy = worldY(y); zoom = Math.max(.25, Math.min(1.6, zoom * Math.pow(1.12, sy)));
        panX = x - left - wx * zoom; panY = y - top - wy * zoom; return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        boolean control = (event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (!document.textMode && event.key() == GLFW.GLFW_KEY_HOME) { fit(); return true; }
        if (control && event.key() == GLFW.GLFW_KEY_S) { apply(); return true; }
        if (control && event.key() == GLFW.GLFW_KEY_Z) { if ((event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0) document.redo(); else document.undo(); propertyDrafts.clear(); propertyEditor = null; selected = null; init(); return true; }
        if (control && event.key() == GLFW.GLFW_KEY_Y) { document.redo(); propertyDrafts.clear(); propertyEditor = null; selected = null; init(); return true; }
        if (!document.textMode && !traceTab && !(getFocused() instanceof EditBox) && !(getFocused() instanceof MultiLineEditBox)) {
            if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) { if (selected != null) { document.checkpoint(); document.graph.remove(selected); selected = null; init(); } return true; }
            if (event.key() == GLFW.GLFW_KEY_ENTER && linkFrom != null) { finishLink(document.graph.nodes.get(selected)); return true; }
            if (event.key() == GLFW.GLFW_KEY_RIGHT || event.key() == GLFW.GLFW_KEY_LEFT) {
                List<String> ids = new ArrayList<>(document.graph.nodes.keySet()); if (!ids.isEmpty()) { int index = ids.indexOf(selected); selected = ids.get(Math.floorMod(index + (event.key() == GLFW.GLFW_KEY_RIGHT ? 1 : -1), ids.size())); center(document.graph.nodes.get(selected)); init(); } return true;
            }
        }
        return super.keyPressed(event);
    }
    @Override public void onClose() {
        try { flux.saveDraft(document.validSource()); } catch (RuntimeException e) { if (document.textMode) flux.saveDraft(document.text); }
        flux.editorDocument = document;
        super.onClose();
    }
}
