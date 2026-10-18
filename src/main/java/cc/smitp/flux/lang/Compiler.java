package cc.smitp.flux.lang;

import java.util.*;
import java.util.function.Predicate;
import static cc.smitp.flux.lang.Program.*;

/** Validates once and lowers structured flow to immutable instructions. */
public final class Compiler {
    public interface Registry {
        boolean knownItem(String id);
        boolean knownTag(String id);
        Set<String> resolveTag(String id);
    }
    public sealed interface Instruction permits Move, Message, Test, Jump {}
    public record Move(Direction direction, Set<String> items, Span span) implements Instruction {}
    public record Message(String text) implements Instruction {}
    public record Test(Condition condition, int whenFalse) implements Instruction {}
    public record Jump(int target) implements Instruction {}
    public record Plan(String name, List<Instruction> instructions, Map<Selector, Set<String>> selectors) {
        public Plan { instructions = List.copyOf(instructions); selectors = Map.copyOf(selectors); }
    }
    public record Result(List<Plan> plans, List<Diagnostic> diagnostics) {
        public boolean valid() { return diagnostics.isEmpty(); }
    }
    private static final Predicate<String> ID = s -> s != null && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public static Result compile(Program program, Registry registry) {
        List<Diagnostic> errors = new ArrayList<>(); List<Plan> plans = new ArrayList<>(); Set<String> names = new HashSet<>();
        if (program.version() != 1) errors.add(new Diagnostic("Unsupported version", Span.GENERATED));
        if (program.workflows().size() > 128) errors.add(new Diagnostic("At most 128 workflows are supported", Span.GENERATED));
        for (Workflow flow : program.workflows()) {
            if (flow.name().isBlank() || !names.add(flow.name())) errors.add(new Diagnostic("Workflow names must be nonempty and unique", flow.span()));
            List<Instruction> code = new ArrayList<>(); Map<Selector, Set<String>> selectors = new HashMap<>();
            lower(flow.body(), code, selectors, errors, registry, 0);
            if (code.size() > 4096) errors.add(new Diagnostic("Workflow exceeds 4096 instructions", flow.span()));
            plans.add(new Plan(flow.name(), code, selectors));
        }
        if (plans.stream().mapToInt(p -> p.instructions().size() + 1).sum() > 4096) errors.add(new Diagnostic("Program exceeds 4096 instructions", Span.GENERATED));
        return new Result(errors.isEmpty() ? List.copyOf(plans) : List.of(), List.copyOf(errors));
    }
    private static void lower(List<Statement> body, List<Instruction> code, Map<Selector, Set<String>> selectors, List<Diagnostic> errors, Registry registry, int depth) {
        if (depth > 64) { errors.add(new Diagnostic("Nesting exceeds 64", Span.GENERATED)); return; }
        for (Statement s : body) switch (s) {
            case Transfer t -> code.add(new Move(t.direction(), resolve(t.selector(), t.span(), selectors, errors, registry), t.span()));
            case Notify n -> code.add(new Message(n.message()));
            case Branch b -> {
                validate(b.condition(), b.span(), selectors, errors, registry, 0);
                int test = code.size(); code.add(new Test(b.condition(), 0)); lower(b.yes(), code, selectors, errors, registry, depth + 1);
                int jump = code.size(); code.add(new Jump(0)); code.set(test, new Test(b.condition(), code.size()));
                lower(b.no(), code, selectors, errors, registry, depth + 1); code.set(jump, new Jump(code.size()));
            }
        }
    }
    private static Set<String> resolve(Selector s, Span span, Map<Selector, Set<String>> selectors, List<Diagnostic> errors, Registry registry) {
        if (selectors.containsKey(s)) return selectors.get(s);
        if (!ID.test(s.id()) || !(s.tag() ? registry.knownTag(s.id()) : registry.knownItem(s.id()))) {
            errors.add(new Diagnostic("Unknown " + (s.tag() ? "tag " : "item ") + s.id(), span)); return Set.of();
        }
        Set<String> items = s.tag() ? Set.copyOf(registry.resolveTag(s.id())) : Set.of(s.id()); selectors.put(s, items); return items;
    }
    private static void validate(Condition c, Span span, Map<Selector, Set<String>> selectors, List<Diagnostic> errors, Registry registry, int depth) {
        if (depth > 64) { errors.add(new Diagnostic("Condition nesting exceeds 64", span)); return; }
        switch (c) {
            case Contains v -> resolve(v.selector(), span, selectors, errors, registry);
            case Location l -> { if (!ID.test(l.dimension())) errors.add(new Diagnostic("Invalid dimension identifier", span)); }
            case ContainerType t -> { if (!Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box", "minecraft:ender_chest").contains(t.id())) errors.add(new Diagnostic("Unsupported container type " + t.id(), span)); }
            case And a -> { validate(a.left(), span, selectors, errors, registry, depth + 1); validate(a.right(), span, selectors, errors, registry, depth + 1); }
            case Or o -> { validate(o.left(), span, selectors, errors, registry, depth + 1); validate(o.right(), span, selectors, errors, registry, depth + 1); }
            case Not n -> validate(n.operand(), span, selectors, errors, registry, depth + 1);
            case Constant ignored -> {}
        }
    }
}
