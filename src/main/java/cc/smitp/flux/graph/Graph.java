package cc.smitp.flux.graph;

import cc.smitp.flux.lang.*;
import java.util.*;
import static cc.smitp.flux.lang.Program.*;
import static cc.smitp.flux.graph.NodeDefinitions.Kind;

/** A structured graph: branches own two subchains and an explicit continuation. */
public final class Graph {
    public static final class Node {
        public final String id;
        public final Kind kind;
        public String value;
        public boolean tag;
        public double x, y;
        public final Map<String, String> outputs = new LinkedHashMap<>();
        public Span span = Span.GENERATED;
        public Node(String id, Kind kind, String value, double x, double y) { this.id = id; this.kind = kind; this.value = value; this.x = x; this.y = y; }
    }
    public static final class Invalid extends IllegalArgumentException {
        public final String node;
        public Invalid(String node, String message) { super(message); this.node = node; }
    }
    public final Map<String, Node> nodes = new LinkedHashMap<>();
    public final List<String> roots = new ArrayList<>();
    public final List<String> comments = new ArrayList<>();
    public Node add(Kind kind, String value, double x, double y) {
        Node n = new Node(UUID.randomUUID().toString(), kind, value, x, y); nodes.put(n.id, n); if (kind == Kind.EVENT) roots.add(n.id); return n;
    }
    public void remove(String id) { nodes.remove(id); roots.remove(id); nodes.values().forEach(n -> n.outputs.values().removeIf(id::equals)); }
    public void connect(String from, String port, String to) {
        Node source = require(from), target = require(to);
        if (!NodeDefinitions.get(source.kind).outputs().contains(port)) throw new Invalid(from, "Unknown output port");
        if (target.kind == Kind.EVENT) throw new Invalid(to, "Events cannot have input connections");
        for (Node n : nodes.values()) for (var e : n.outputs.entrySet()) if (e.getValue().equals(to) && !(n.id.equals(from) && e.getKey().equals(port))) throw new Invalid(to, "Input already connected; arbitrary merges are not supported");
        String previous = source.outputs.put(port, to);
        try { checkCycles(); } catch (RuntimeException e) { if (previous == null) source.outputs.remove(port); else source.outputs.put(port, previous); throw e; }
    }
    public Program program() {
        checkCycles(); Set<String> visited = new HashSet<>(); List<Workflow> flows = new ArrayList<>();
        for (String id : roots) {
            Node root = require(id); if (root.kind != Kind.EVENT) throw new Invalid(id, "Expected event root");
            if (!visited.add(id)) throw new Invalid(id, "Duplicate workflow root");
            flows.add(new Workflow(root.value, chain(root.outputs.get("next"), visited, 0), root.span));
        }
        if (visited.size() != nodes.size()) throw new Invalid(nodes.keySet().stream().filter(id -> !visited.contains(id)).findFirst().orElse(""), "Disconnected node: connect it or delete it before Apply");
        return new Program(1, flows, comments);
    }
    private List<Statement> chain(String id, Set<String> visited, int depth) {
        if (depth > 64) throw new Invalid(id, "Nesting exceeds 64");
        List<Statement> body = new ArrayList<>();
        while (id != null) {
            Node n = require(id); if (!visited.add(id)) throw new Invalid(id, "Shared branch/merge is not supported");
            try {
                body.add(switch (n.kind) {
                    case EVENT -> throw new Invalid(n.id, "Event inside workflow");
                    case DEPOSIT, WITHDRAW -> new Transfer(n.kind == Kind.DEPOSIT ? Direction.DEPOSIT : Direction.WITHDRAW, new Selector(n.tag, n.value), n.span);
                    case NOTIFY -> new Notify(n.value, n.span);
                    case BRANCH -> new Branch(Parser.parseCondition(n.value), chain(n.outputs.get("yes"), visited, depth + 1), chain(n.outputs.get("no"), visited, depth + 1), n.span);
                });
            } catch (Diagnostic.Failure e) { throw new Invalid(n.id, e.diagnostic().toString()); }
            id = n.outputs.get("next");
        }
        return body;
    }
    private Node require(String id) { Node n = nodes.get(id); if (n == null) throw new Invalid(id, "Missing node"); return n; }
    private void checkCycles() {
        if (nodes.size() > 4096) throw new Invalid("", "Graph exceeds 4096 nodes");
        // Iterative DFS keeps long but bounded sequences off the JVM call stack.
        record Visit(String id, boolean exit) {}
        Map<String, Integer> colors = new HashMap<>();
        for (String root : nodes.keySet()) {
            if (colors.getOrDefault(root, 0) == 2) continue;
            Deque<Visit> stack = new ArrayDeque<>(); stack.push(new Visit(root, false));
            while (!stack.isEmpty()) {
                Visit v = stack.pop();
                if (v.exit()) { colors.put(v.id(), 2); continue; }
                int color = colors.getOrDefault(v.id(), 0);
                if (color == 1) throw new Invalid(v.id(), "Cycles are not allowed");
                if (color == 2) continue;
                Node n = require(v.id()); colors.put(v.id(), 1); stack.push(new Visit(v.id(), true));
                for (var e : n.outputs.entrySet()) {
                    if (!NodeDefinitions.get(n.kind).outputs().contains(e.getKey())) throw new Invalid(n.id, "Invalid port " + e.getKey());
                    stack.push(new Visit(e.getValue(), false));
                }
            }
        }
    }
    public static Graph from(Program program) {
        Graph graph = new Graph(); graph.comments.addAll(program.comments());
        int row = 0;
        for (Workflow w : program.workflows()) {
            Node root = graph.add(Kind.EVENT, w.name(), 0, row); root.span = w.span();
            String first = graph.expand(w.body(), 230, row);
            if (first != null) root.outputs.put("next", first);
            row = (int) graph.nodes.values().stream().mapToDouble(n -> n.y).max().orElse(row) + 230;
        }
        return graph;
    }
    private String expand(List<Statement> body, int x, int y) {
        String first = null; Node previous = null;
        for (Statement s : body) {
            Node node = switch (s) {
                case Transfer t -> { Node n = add(t.direction() == Direction.DEPOSIT ? Kind.DEPOSIT : Kind.WITHDRAW, t.selector().id(), x, y); n.tag = t.selector().tag(); yield n; }
                case Notify n -> add(Kind.NOTIFY, n.message(), x, y);
                case Branch b -> {
                    Node n = add(Kind.BRANCH, cc.smitp.flux.lang.Formatter.condition(b.condition()), x, y);
                    String yes = expand(b.yes(), x + 230, y + 150);
                    int bottom = (int) nodes.values().stream().mapToDouble(v -> v.y).max().orElse(y);
                    String no = expand(b.no(), x + 230, bottom + 150);
                    if (yes != null) n.outputs.put("yes", yes); if (no != null) n.outputs.put("no", no); yield n;
                }
            };
            node.span = s.span(); if (first == null) first = node.id;
            if (previous != null) previous.outputs.put("next", node.id);
            previous = node; x = node.kind == Kind.BRANCH ? (int) subtreeMaxX(node) + 230 : x + 230;
        }
        return first;
    }
    private double subtreeMaxX(Node root) {
        double max = root.x; Deque<Node> pending = new ArrayDeque<>(); pending.add(root);
        while (!pending.isEmpty()) {
            Node n = pending.removeFirst(); max = Math.max(max, n.x);
            n.outputs.values().forEach(id -> pending.add(require(id)));
        }
        return max;
    }
    public Graph copy() {
        Graph g = new Graph(); g.roots.addAll(roots); g.comments.addAll(comments);
        for (Node n : nodes.values()) { Node v = new Node(n.id, n.kind, n.value, n.x, n.y); v.tag = n.tag; v.span = n.span; v.outputs.putAll(n.outputs); g.nodes.put(v.id, v); } return g;
    }
}
