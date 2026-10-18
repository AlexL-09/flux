package cc.smitp.flux.lang;

import java.util.List;

/** Immutable, game-independent language model shared by both editors. */
public record Program(int version, List<Workflow> workflows, List<String> comments) {
    public Program { workflows = List.copyOf(workflows); comments = List.copyOf(comments); }
    public record Span(int start, int end, int line, int column) {
        public static final Span GENERATED = new Span(0, 0, 1, 1);
    }
    public record Workflow(String name, List<Statement> body, Span span) {
        public Workflow { body = List.copyOf(body); }
    }
    public enum Direction { DEPOSIT, WITHDRAW }
    public enum Source { PLAYER, CONTAINER }
    public record Selector(boolean tag, String id) {}
    public record Position(int x, int y, int z) {}
    public sealed interface Statement permits Transfer, Notify, Branch { Span span(); }
    public record Transfer(Direction direction, Selector selector, Span span) implements Statement {}
    public record Notify(String message, Span span) implements Statement {}
    public record Branch(Condition condition, List<Statement> yes, List<Statement> no, Span span) implements Statement {
        public Branch { yes = List.copyOf(yes); no = List.copyOf(no); }
    }
    public sealed interface Condition permits Location, ContainerType, Contains, And, Or, Not, Constant {}
    public record Location(String dimension, Position position) implements Condition {}
    public record ContainerType(String id) implements Condition {}
    public record Contains(Source source, Selector selector) implements Condition {}
    public record And(Condition left, Condition right) implements Condition {}
    public record Or(Condition left, Condition right) implements Condition {}
    public record Not(Condition operand) implements Condition {}
    public record Constant(boolean value) implements Condition {}
}
