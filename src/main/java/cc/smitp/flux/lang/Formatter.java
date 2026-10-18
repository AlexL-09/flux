package cc.smitp.flux.lang;

import java.util.List;
import static cc.smitp.flux.lang.Program.*;

public final class Formatter {
    private Formatter() {}
    public static String format(Program program) {
        StringBuilder out = new StringBuilder("version " + program.version() + ";\n");
        for (String comment : program.comments()) out.append("// ").append(comment.replace("\n", " ")).append('\n');
        for (Workflow w : program.workflows()) {
            out.append("\nworkflow ").append(quote(w.name())).append(" {\n  on container.opened;\n");
            statements(out, w.body(), 1); out.append("}\n");
        }
        return out.toString();
    }
    public static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""; }
    public static String selector(Selector s) { return (s.tag() ? "tag(" : "item(") + quote(s.id()) + ")"; }
    public static String condition(Condition c) {
        return switch (c) {
            case Location l -> "container.matches(dimension: " + quote(l.dimension()) + ", position: [" + l.position().x() + ", " + l.position().y() + ", " + l.position().z() + "])";
            case ContainerType t -> "container.type(" + quote(t.id()) + ")";
            case Contains v -> v.source().name().toLowerCase(java.util.Locale.ROOT) + ".contains(" + selector(v.selector()) + ")";
            case And a -> "(" + condition(a.left()) + " && " + condition(a.right()) + ")";
            case Or o -> "(" + condition(o.left()) + " || " + condition(o.right()) + ")";
            case Not n -> "!(" + condition(n.operand()) + ")";
            case Constant k -> Boolean.toString(k.value());
        };
    }
    private static void statements(StringBuilder out, List<Statement> list, int indent) {
        for (Statement s : list) {
            out.append("  ".repeat(indent));
            switch (s) {
                case Transfer t -> out.append(t.direction().name().toLowerCase(java.util.Locale.ROOT)).append(" all ").append(selector(t.selector())).append(";\n");
                case Notify n -> out.append("notify ").append(quote(n.message())).append(";\n");
                case Branch b -> {
                    out.append("if ").append(condition(b.condition())).append(" {\n"); statements(out, b.yes(), indent + 1);
                    out.append("  ".repeat(indent)).append('}');
                    if (!b.no().isEmpty()) { out.append(" else {\n"); statements(out, b.no(), indent + 1); out.append("  ".repeat(indent)).append('}'); }
                    out.append('\n');
                }
            }
        }
    }
}
