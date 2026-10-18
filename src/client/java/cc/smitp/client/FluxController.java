package cc.smitp.client;

import cc.smitp.flux.graph.Graph;
import cc.smitp.flux.lang.*;
import cc.smitp.flux.lang.Compiler;
import cc.smitp.flux.runtime.*;
import cc.smitp.flux.storage.ProgramRepository;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.*;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class FluxController {
    public static FluxController INSTANCE;
    private final Minecraft minecraft;
    public final ContainerSessionTracker tracker;
    public final MinecraftInventoryAdapter inventory;
    public final TraceBuffer trace = new TraceBuffer();
    public final WorkflowRuntime runtime;
    public final ProgramRepository repository;
    public ProgramRepository.Metadata metadata;
    public cc.smitp.flux.graph.EditorDocument editorDocument;
    public String source, draft, status = "Join a world to validate item selectors";
    public long ticks, activeNanos, activeSamples, maxActiveNanos;
    private Object connection, level;
    private String profile;
    private List<Compiler.Plan> plans = List.of();
    private boolean sourceValidated;
    public FluxController(Minecraft minecraft) {
        this.minecraft = minecraft; tracker = new ContainerSessionTracker(minecraft); inventory = new MinecraftInventoryAdapter(minecraft, tracker);
        repository = new ProgramRepository(FabricLoader.getInstance().getConfigDir().resolve("flux"));
        try { metadata = repository.loadMetadata(); } catch (IOException e) { metadata = new ProgramRepository.Metadata(); status = e.getMessage(); }
        try { source = repository.loadSource(); draft = repository.loadDraft(source); }
        catch (IOException e) { source = ProgramRepository.EXAMPLE; draft = source; status = e.getMessage(); }
        runtime = new WorkflowRuntime(inventory, trace, metadata.limits);
    }
    public Compiler.Result validate(Program program) { return Compiler.compile(program, registry()); }
    private Compiler.Registry registry() {
        return new Compiler.Registry() {
            public boolean knownItem(String id) { return BuiltInRegistries.ITEM.containsKey(Identifier.parse(id)); }
            public boolean knownTag(String id) { return BuiltInRegistries.ITEM.get(TagKey.create(Registries.ITEM, Identifier.parse(id))).isPresent(); }
            public Set<String> resolveTag(String id) {
                Set<String> ids = new HashSet<>();
                BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, Identifier.parse(id))).forEach(h -> ids.add(BuiltInRegistries.ITEM.getKey(h.value()).toString())); return ids;
            }
        };
    }
    public String apply(String text, Graph graph) {
        if (profile == null) return "Join a world before applying workflows";
        try {
            Program p = Parser.parse(text); Compiler.Result result = validate(p);
            if (!result.valid()) return result.diagnostics().getFirst().toString();
            // Also require graph representability, including connection/size limits.
            Graph canonical = Graph.from(p); canonical.program();
            String formatted = cc.smitp.flux.lang.Formatter.format(p);
            var nextMetadata = ProgramRepository.copyMetadata(metadata);
            ProgramRepository.captureLayout(graph == null ? canonical : graph, nextMetadata);
            repository.save(formatted, nextMetadata, sourceValidated ? source : null);
            metadata = nextMetadata; sourceValidated = true;
            runtime.cancel("Program replaced", ticks); plans = result.plans(); source = formatted; draft = formatted;
            status = "Applied " + plans.size() + " workflows";
            try { repository.saveDraft(draft); } catch (IOException e) { status += "; draft save failed: " + e.getMessage(); }
            return null;
        } catch (Diagnostic.Failure e) { return e.diagnostic().toString(); }
        catch (IllegalArgumentException | IOException e) { return e.getMessage(); }
    }
    public void recompile() {
        runtime.cancel("Registry or profile changed", ticks);
        try { Compiler.Result result = validate(Parser.parse(source)); plans = result.plans(); sourceValidated = result.valid(); status = result.valid() ? "Ready · " + enabledPlans().size() + " enabled" : result.diagnostics().getFirst().toString(); }
        catch (Diagnostic.Failure e) { plans = List.of(); sourceValidated = false; status = e.diagnostic().toString(); }
    }
    public boolean connected() { return profile != null; }
    public String profileLabel() { return profile == null ? "Offline" : (minecraft.getSingleplayerServer() != null ? "Local world" : minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().ip : "Server"); }
    public boolean enabled(String name) { return profile != null && metadata.enabled.getOrDefault(profile, Set.of()).contains(name); }
    public String toggle(String name) {
        if (profile == null) return "Join a world first";
        if (plans.stream().noneMatch(p -> p.name().equals(name))) return "Apply this workflow before enabling it";
        Set<String> names = metadata.enabled.computeIfAbsent(profile, ignored -> new LinkedHashSet<>()); boolean previous = names.contains(name);
        if (previous) names.remove(name); else names.add(name);
        try { repository.saveMetadata(metadata); runtime.cancel("Enablement changed", ticks); return (previous ? "Disabled " : "Enabled ") + name; }
        catch (IOException e) { if (previous) names.add(name); else names.remove(name); return "Save failed: " + e.getMessage(); }
    }
    public void saveDraft(String text) { draft = text; try { repository.saveDraft(text); } catch (IOException e) { status = "Draft save failed: " + e.getMessage(); } }
    public void tick() {
        ticks++;
        if (connection != minecraft.getConnection() || level != minecraft.level) {
            boolean newConnection = connection != minecraft.getConnection(); connection = minecraft.getConnection(); level = minecraft.level;
            runtime.cancel("World or connection changed", ticks); inventory.reset(); if (newConnection) tracker.resetConnection(); else tracker.clear();
            String identity = null;
            if (connection != null && level != null) {
                if (minecraft.getSingleplayerServer() != null) identity = "save:" + minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                else if (minecraft.getCurrentServer() != null) identity = "server:" + minecraft.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            }
            String next = identity == null ? null : ProgramRepository.profileKey(identity);
            if (!Objects.equals(profile, next)) tracker.resetConnection();
            profile = next; if (profile != null) recompile(); else plans = List.of();
        }
        if (runtime.active()) {
            long start = System.nanoTime();
            try { runtime.tick(ticks); }
            catch (RuntimeException e) {
                runtime.cancel("Unexpected adapter failure: " + e.getClass().getSimpleName(), ticks);
                status = "Automation stopped after an adapter error; see latest.log";
                org.slf4j.LoggerFactory.getLogger("flux").error("Inventory execution failed", e);
            }
            long elapsed = System.nanoTime() - start;
            activeNanos += elapsed; activeSamples++; maxActiveNanos = Math.max(maxActiveNanos, elapsed);
        }
    }
    private List<Compiler.Plan> enabledPlans() {
        return plans.stream().filter(p -> enabled(p.name())).sorted(Comparator.comparingInt(p -> { int i = metadata.workflowOrder.indexOf(p.name()); return i < 0 ? Integer.MAX_VALUE : i; })).collect(Collectors.toList());
    }
    public void opened() { runtime.cancel("New container", ticks); inventory.reset(); tracker.opened(ticks); }
    public void contentsReady() { runtime.opened(enabledPlans(), ticks); }
    public void manualInput() { if (!inventory.automatedClick) runtime.cancel("Manual inventory interaction", ticks); }
    public void closeContainer() { runtime.cancel("Container closed", ticks); inventory.reset(); tracker.clear(); }
    public void stop() { runtime.cancel("Emergency stop", ticks); status = "Stopped"; inventory.notifyLocal("Automation stopped"); }
    public String diagnostics() { return status + "; " + (runtime.active() ? "running" : "idle") + "; active tick mean " + String.format(Locale.ROOT, "%.3f", activeSamples == 0 ? 0 : activeNanos / (double) activeSamples / 1e6) + " ms, max " + String.format(Locale.ROOT, "%.3f", maxActiveNanos / 1e6) + " ms (" + activeSamples + " samples)"; }
}
