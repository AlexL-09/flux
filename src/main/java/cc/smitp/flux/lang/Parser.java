package cc.smitp.flux.lang;

import java.util.ArrayList;
import java.util.List;
import static cc.smitp.flux.lang.Program.*;

/** Deliberately bounded recursive-descent parser. Comments survive canonical formatting. */
public final class Parser {
    private record Token(String value, boolean string, Span span) {}
    private final List<Token> tokens = new ArrayList<>();
    private final List<String> comments = new ArrayList<>();
    private int cursor, depth;
    public static Program parse(String source) { return new Parser(source).program(); }
    public static Condition parseCondition(String source) {
        Parser p = new Parser(source); Condition c = p.condition(); p.expect("<eof>"); return c;
    }
    private Parser(String source) {
        if (source.length() > 262144) throw new Diagnostic.Failure("Program exceeds 256 KiB", Span.GENERATED);
        int i = 0, line = 1, col = 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; if (c == '\n') { line++; col = 1; } else col++; continue; }
            int start = i, column = col, row = line;
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i += 2; int from = i;
                while (i < source.length() && source.charAt(i) != '\n') i++;
                comments.add(source.substring(from, i).strip()); col += i - start; continue;
            }
            boolean string = c == '"'; String value;
            if (string) {
                i++; StringBuilder b = new StringBuilder(); boolean closed = false;
                while (i < source.length()) {
                    char ch = source.charAt(i++);
                    if (ch == '"') { closed = true; break; }
                    if (ch == '\n' || ch == '\r') fail("Use \\n inside strings", start, i, row, column);
                    if (ch == '\\') {
                        if (i >= source.length()) fail("Unterminated escape", start, i, row, column);
                        ch = source.charAt(i++);
                        ch = switch (ch) { case 'n' -> '\n'; case 't' -> '\t'; case 'r' -> '\r'; case '"' -> '"'; case '\\' -> '\\'; default -> throw new Diagnostic.Failure("Unknown escape", new Span(start, i, row, column)); };
                    }
                    b.append(ch);
                }
                if (!closed) fail("Unterminated string", start, i, row, column);
                value = b.toString();
            } else if (Character.isLetter(c) || c == '_' || Character.isDigit(c) || c == '-') {
                i++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || "_.-".indexOf(source.charAt(i)) >= 0)) i++;
                value = source.substring(start, i);
            } else if ("{}()[]:;,!".indexOf(c) >= 0) { value = String.valueOf(c); i++; }
            else if ((c == '&' || c == '|') && i + 1 < source.length() && source.charAt(i + 1) == c) { value = source.substring(i, i + 2); i += 2; }
            else throw new Diagnostic.Failure("Unexpected character '" + c + "'", new Span(start, i + 1, row, column));
            tokens.add(new Token(value, string, new Span(start, i, row, column))); col += i - start;
            if (tokens.size() > 20000) fail("Too many tokens", start, i, row, column);
        }
        tokens.add(new Token("<eof>", false, new Span(i, i, line, col)));
    }
    private static void fail(String message, int start, int end, int line, int col) { throw new Diagnostic.Failure(message, new Span(start, end, line, col)); }
    private Program program() {
        expect("version"); int version = integer(); expect(";");
        if (version != 1) throw error("Unsupported language version " + version);
        List<Workflow> flows = new ArrayList<>();
        while (!at("<eof>")) {
            Span span = peek().span; expect("workflow"); String name = string(); expect("{");
            expect("on"); expect("container.opened"); expect(";");
            flows.add(new Workflow(name, statements(), span));
        }
        return new Program(version, flows, comments);
    }
    private List<Statement> statements() {
        enter(); List<Statement> out = new ArrayList<>();
        while (!at("}")) {
            Span span = peek().span;
            if (take("if")) {
                Condition c = condition(); expect("{"); List<Statement> yes = statements();
                List<Statement> no = List.of(); if (take("else")) { expect("{"); no = statements(); }
                out.add(new Branch(c, yes, no, span));
            } else if (at("deposit") || at("withdraw")) {
                Direction d = take("deposit") ? Direction.DEPOSIT : Direction.WITHDRAW;
                if (d == Direction.WITHDRAW) expect("withdraw");
                expect("all"); Selector s = selector(); expect(";"); out.add(new Transfer(d, s, span));
            } else if (take("notify")) { String message = string(); expect(";"); out.add(new Notify(message, span)); }
            else throw error("Expected if, deposit, withdraw, notify, or }");
        }
        expect("}"); depth--; return out;
    }
    private Condition condition() {
        enter(); Condition c = and(); while (take("||")) c = new Or(c, and()); depth--; return c;
    }
    private Condition and() { Condition c = unary(); while (take("&&")) c = new And(c, unary()); return c; }
    private Condition unary() {
        enter(); Condition result;
        if (take("!")) result = new Not(unary());
        else if (take("(")) { result = condition(); expect(")"); }
        else if (take("true")) result = new Constant(true);
        else if (take("false")) result = new Constant(false);
        else if (take("container.matches")) {
            expect("("); expect("dimension"); expect(":"); String dim = string(); expect(",");
            expect("position"); expect(":"); expect("["); int x = integer(); expect(","); int y = integer(); expect(","); int z = integer(); expect("]"); expect(")");
            result = new Location(dim, new Position(x, y, z));
        } else if (take("container.type")) { expect("("); String type = string(); expect(")"); result = new ContainerType(type); }
        else if (at("player.contains") || at("container.contains")) {
            Source s = take("player.contains") ? Source.PLAYER : Source.CONTAINER;
            if (s == Source.CONTAINER) expect("container.contains");
            expect("("); Selector selector = selector(); expect(")"); result = new Contains(s, selector);
        } else throw error("Expected a condition");
        depth--; return result;
    }
    private Selector selector() {
        boolean tag = take("tag"); if (!tag) expect("item"); expect("("); String id = string(); expect(")"); return new Selector(tag, id);
    }
    private void enter() { if (++depth > 512) throw error("Parser nesting exceeds 512 levels"); }
    private int integer() { Token t = peek(); if (t.string || !t.value.matches("-?[0-9]+")) throw error("Expected an integer"); cursor++; try { return Integer.parseInt(t.value); } catch (NumberFormatException e) { throw new Diagnostic.Failure("Expected a 32-bit integer", t.span); } }
    private String string() { if (!peek().string) throw error("Expected a quoted string"); return tokens.get(cursor++).value; }
    private Token peek() { return tokens.get(Math.min(cursor, tokens.size() - 1)); }
    private boolean at(String value) { return !peek().string && peek().value.equals(value); }
    private boolean take(String value) { if (!at(value)) return false; cursor++; return true; }
    private void expect(String value) { if (!take(value)) throw error("Expected '" + value + "', got '" + peek().value + "'"); }
    private Diagnostic.Failure error(String message) { return new Diagnostic.Failure(message, peek().span); }
}
