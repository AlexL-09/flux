package cc.smitp.flux.lang;

public record Diagnostic(String message, Program.Span span) {
    @Override public String toString() { return span.line() + ":" + span.column() + " " + message; }
    public static final class Failure extends IllegalArgumentException {
        private final Diagnostic diagnostic;
        public Failure(String message, Program.Span span) { super(message); diagnostic = new Diagnostic(message, span); }
        public Diagnostic diagnostic() { return diagnostic; }
    }
}
