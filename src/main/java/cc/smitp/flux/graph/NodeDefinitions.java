package cc.smitp.flux.graph;

import java.util.List;

/** Palette and port schema shared by the graph validator and screen. */
public final class NodeDefinitions {
    public enum Kind { EVENT, BRANCH, DEPOSIT, WITHDRAW, NOTIFY }
    public record Definition(Kind kind, String label, int color, List<String> outputs, String propertyHint) {}
    public static final List<Definition> ALL = List.of(
        new Definition(Kind.EVENT, "Container opened", 0xFF54B8C5, List.of("next"), "Workflow name"),
        new Definition(Kind.BRANCH, "Condition", 0xFFDCA85E, List.of("yes", "no", "next"), "Condition expression"),
        new Definition(Kind.DEPOSIT, "Deposit all", 0xFF8DAE78, List.of("next"), "item/tag identifier"),
        new Definition(Kind.WITHDRAW, "Withdraw all", 0xFF9E94DB, List.of("next"), "item/tag identifier"),
        new Definition(Kind.NOTIFY, "Local notification", 0xFFB5BFCB, List.of("next"), "Message")
    );
    public static Definition get(Kind kind) { return ALL.stream().filter(d -> d.kind() == kind).findFirst().orElseThrow(); }
}
