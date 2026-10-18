package cc.smitp.flux.storage;

import cc.smitp.flux.graph.Graph;
import cc.smitp.flux.lang.*;
import cc.smitp.flux.runtime.WorkflowRuntime.Limits;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Saves validated source and editor metadata; invalid drafts never replace active source. */
public final class ProgramRepository {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    public record Point(double x, double y) {}
    public static final class Metadata {
        public int version = 1;
        public Map<String, Set<String>> enabled = new LinkedHashMap<>();
        public Map<String, Point> positions = new LinkedHashMap<>();
        public List<String> workflowOrder = new ArrayList<>();
        public Limits limits = Limits.DEFAULT;
    }
    public ProgramRepository(Path directory) { this.directory = directory; }
    public String loadSource() throws IOException {
        Path path = directory.resolve("workflows.flux");
        if (!Files.exists(path)) return EXAMPLE;
        if (Files.size(path) > 262144) throw new IOException("Program exceeds 256 KiB");
        return Files.readString(path);
    }
    public String loadDraft(String fallback) throws IOException { Path p = directory.resolve("draft.flux"); return Files.exists(p) && Files.size(p) <= 262144 ? Files.readString(p) : fallback; }
    public Metadata loadMetadata() throws IOException {
        Path path = directory.resolve("editor.json"); if (!Files.exists(path)) return new Metadata();
        if (Files.size(path) > 2_000_000) throw new IOException("Editor metadata too large");
        try {
            Metadata meta = JSON.fromJson(Files.readString(path), Metadata.class);
            if (meta == null || meta.version != 1 || meta.enabled == null || meta.positions == null || meta.workflowOrder == null || meta.limits == null) throw new IOException("Unsupported or invalid editor metadata");
            new Limits(meta.limits.clickInterval(), meta.limits.confirmationTimeout(), meta.limits.transitions(), meta.limits.deadline());
            if (meta.enabled.values().stream().anyMatch(Objects::isNull) || meta.positions.values().stream().anyMatch(p -> p == null || !Double.isFinite(p.x()) || !Double.isFinite(p.y()))) throw new IOException("Invalid profile or canvas metadata");
            return meta;
        } catch (JsonParseException | IllegalArgumentException e) { throw new IOException("Invalid editor metadata", e); }
    }
    public void save(String validatedSource, Metadata meta) throws IOException {
        save(validatedSource, meta, Files.exists(directory.resolve("workflows.flux")) ? loadSource() : null);
    }
    public void save(String validatedSource, Metadata meta, String previousValidSource) throws IOException {
        saveMetadata(meta);
        // Never replace a known-good backup with an externally corrupted file.
        if (previousValidSource != null) atomic(directory.resolve("workflows.flux.bak"), previousValidSource, false);
        // Source replacement is the commit point. A failed source save cannot activate new code.
        atomic(directory.resolve("workflows.flux"), validatedSource, false);
    }
    public static Metadata copyMetadata(Metadata original) {
        Metadata copy = new Metadata(); copy.version = original.version; copy.limits = original.limits;
        original.enabled.forEach((key, value) -> copy.enabled.put(key, new LinkedHashSet<>(value)));
        copy.positions.putAll(original.positions); copy.workflowOrder.addAll(original.workflowOrder); return copy;
    }
    public void saveMetadata(Metadata meta) throws IOException { atomic(directory.resolve("editor.json"), JSON.toJson(meta), true); }
    public void saveDraft(String draft) throws IOException { atomic(directory.resolve("draft.flux"), draft, false); }
    private void atomic(Path target, String content, boolean backup) throws IOException {
        Files.createDirectories(directory); Path tmp = Files.createTempFile(directory, ".flux-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            if (backup && Files.exists(target)) {
                Path backupPath = target.resolveSibling(target.getFileName() + ".bak");
                Path backupTemp = Files.createTempFile(directory, ".backup-", ".tmp");
                try { Files.copy(target, backupTemp, StandardCopyOption.REPLACE_EXISTING); replace(backupTemp, backupPath); }
                finally { Files.deleteIfExists(backupTemp); }
            }
            replace(tmp, target);
        } finally { Files.deleteIfExists(tmp); }
    }
    private static void replace(Path from, Path to) throws IOException { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
    public static String profileKey(String identity) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    /** Structural paths remain stable across parsing; positions do not depend on random node IDs. */
    public static void captureLayout(Graph graph, Metadata meta) { meta.positions.clear(); paths(graph).forEach((path, n) -> meta.positions.put(path, new Point(n.x, n.y))); meta.workflowOrder = graph.roots.stream().map(id -> graph.nodes.get(id).value).toList(); }
    public static void restoreLayout(Graph graph, Metadata meta) { paths(graph).forEach((path, n) -> { Point p = meta.positions.get(path); if (p != null) { n.x = p.x(); n.y = p.y(); } }); }
    private static Map<String, Graph.Node> paths(Graph graph) {
        Map<String, Graph.Node> paths = new LinkedHashMap<>(); Set<String> seen = new HashSet<>();
        for (String root : graph.roots) walk(graph, root, Base64.getUrlEncoder().withoutPadding().encodeToString(graph.nodes.get(root).value.getBytes(StandardCharsets.UTF_8)), paths, seen);
        return paths;
    }
    private static void walk(Graph graph, String id, String path, Map<String, Graph.Node> out, Set<String> seen) {
        record Step(String id, String path) {}
        Deque<Step> todo = new ArrayDeque<>(); todo.push(new Step(id, path));
        while (!todo.isEmpty()) {
            Step step = todo.pop(); if (!seen.add(step.id())) continue;
            Graph.Node n = graph.nodes.get(step.id()); if (n == null) continue;
            out.put(step.path(), n);
            n.outputs.forEach((port, target) -> todo.push(new Step(target, step.path() + "/" + port)));
        }
    }
    public static final String EXAMPLE = """
        version 1;
        // Open a chest, then use Capture in the editor to set its location.
        workflow "Farm storage" {
          on container.opened;
          if container.matches(dimension: "minecraft:overworld", position: [120, 64, -30]) {
            deposit all item("minecraft:carrot");
            deposit all tag("minecraft:logs");
            notify "Farm storage finished";
          }
        }
        """;
}
