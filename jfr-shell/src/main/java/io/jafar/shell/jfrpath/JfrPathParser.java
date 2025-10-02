package io.jafar.shell.jfrpath;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.jafar.shell.jfrpath.JfrPath.*;

/**
 * Minimal JfrPath parser v0.
 * Supports: root segment (events|metadata|chunks|cp), path segments, filters in brackets
 * like: events/jdk.ExecutionSample[thread/name~"main"][duration>10]
 */
public final class JfrPathParser {
    private final String input;
    private int pos = 0;

    private JfrPathParser(String input) {
        this.input = input;
    }

    public static Query parse(String input) {
        return new JfrPathParser(input).parseQuery();
    }

    private Query parseQuery() {
        skipWs();
        String rootTok = readUntil('/', '[', ' ');
        Root root = switch (rootTok.toLowerCase(Locale.ROOT)) {
            case "events" -> Root.EVENTS;
            case "metadata" -> Root.METADATA;
            case "chunks" -> Root.CHUNKS;
            case "cp" -> Root.CP;
            default -> throw error("Unknown root: " + rootTok);
        };
        if (peek() == '/') pos++;
        List<String> segments = new ArrayList<>();
        // read path segments until filter or end
        while (!eof()) {
            if (peek() == '[' || peek() == '|' || Character.isWhitespace((char) peek())) break;
            String seg = readUntil('/', '[', ' ', '|');
            if (!seg.isEmpty()) segments.add(seg);
            if (peek() == '/') pos++;
            else break;
        }
        List<Predicate> preds = new ArrayList<>();
        while (!eof() && peek() == '[') {
            pos++; // [
            preds.add(parsePredicate());
            expect(']');
        }
        // Optional pipeline: | fn(args)? (| fn(args)?)*
        List<JfrPath.PipelineOp> pipeline = new ArrayList<>();
        skipWs();
        while (!eof() && peek() == '|') {
            pos++; // |
            skipWs();
            pipeline.add(parsePipelineOp());
            skipWs();
        }
        skipWs();
        if (!eof()) throw error("Trailing characters at position " + pos);
        return new Query(root, segments, preds, pipeline);
    }

    private Predicate parsePredicate() {
        skipWs();
        List<String> fieldPath = new ArrayList<>();
        // Optional list match mode prefix: any: | all: | none:
        MatchMode mode = null;
        int save = pos;
        String maybeMode = readIdent();
        if (!maybeMode.isEmpty() && (peek() == ':' )) {
            // consume ':' and whitespace
            pos++; // ':'
            String mm = maybeMode.toLowerCase(Locale.ROOT);
            switch (mm) {
                case "any" -> mode = MatchMode.ANY;
                case "all" -> mode = MatchMode.ALL;
                case "none" -> mode = MatchMode.NONE;
                default -> { /* not a mode */ mode = null; pos = save; }
            }
        } else {
            // not a mode prefix, rewind
            pos = save;
        }
        // parse field path like a/b/c
        while (!eof()) {
            String seg = readIdent();
            if (seg.isEmpty()) throw error("Expected field name in filter");
            fieldPath.add(seg);
            if (peek() == '/') { pos++; continue; }
            break;
        }
        skipWs();
        Op op;
        if (match("~")) op = Op.REGEX;
        else if (match(">=")) op = Op.GE;
        else if (match("<=")) op = Op.LE;
        else if (match("==") || match("=")) op = Op.EQ;
        else if (match("!=")) op = Op.NE;
        else if (match(">")) op = Op.GT;
        else if (match("<")) op = Op.LT;
        else throw error("Expected operator after field path");
        skipWs();
        Object lit = parseLiteral();
        if (mode != null) {
            return new FieldPredicate(fieldPath, op, lit, mode);
        }
        return new FieldPredicate(fieldPath, op, lit);
    }

    private Object parseLiteral() {
        if (peek() == '"' || peek() == '\'') {
            char quote = (char) peek();
            pos++;
            StringBuilder sb = new StringBuilder();
            while (!eof() && peek() != quote) {
                char c = (char) peek();
                if (c == '\\') {
                    pos++;
                    if (eof()) break;
                    c = (char) peek();
                }
                sb.append(c);
                pos++;
            }
            expect(quote);
            return sb.toString();
        }
        // boolean literals
        if (startsWithIgnoreCase("true")) { pos += 4; return Boolean.TRUE; }
        if (startsWithIgnoreCase("false")) { pos += 5; return Boolean.FALSE; }

        // number (int or decimal)
        int start = pos;
        boolean dot = false;
        while (!eof()) {
            char c = (char) peek();
            if (Character.isDigit(c)) { pos++; continue; }
            if (c == '.' && !dot) { dot = true; pos++; continue; }
            break;
        }
        if (pos == start) throw error("Expected literal");
        String num = input.substring(start, pos);
        try {
            if (dot) return Double.parseDouble(num);
            return Long.parseLong(num);
        } catch (NumberFormatException e) {
            throw error("Invalid number literal: " + num);
        }
    }

    private String readIdent() {
        int start = pos;
        while (!eof()) {
            char c = (char) peek();
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '-') { pos++; }
            else break;
        }
        return input.substring(start, pos);
    }

    private String readUntil(char... terms) {
        int start = pos;
        outer: while (!eof()) {
            char c = (char) peek();
            for (char t : terms) {
                if (c == t) break outer;
            }
            pos++;
        }
        return input.substring(start, pos).trim();
    }

    private void skipWs() {
        while (!eof() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private boolean match(String s) {
        if (input.startsWith(s, pos)) { pos += s.length(); return true; }
        return false;
    }

    private boolean startsWithIgnoreCase(String s) {
        int n = s.length();
        if (pos + n > input.length()) return false;
        for (int i = 0; i < n; i++) {
            char a = Character.toLowerCase(input.charAt(pos + i));
            char b = Character.toLowerCase(s.charAt(i));
            if (a != b) return false;
        }
        return true;
    }

    private JfrPath.PipelineOp parsePipelineOp() {
        String name = readIdent().toLowerCase(Locale.ROOT);
        skipWs();
        List<String> valuePath = List.of();
        if ("count".equals(name)) {
            if (peek() == '(') { pos++; skipWs(); expect(')'); }
            return new JfrPath.CountOp();
        } else if ("stats".equals(name)) {
            if (peek() == '(') {
                pos++; skipWs();
                if (peek() != ')') {
                    valuePath = parsePathArg();
                }
                expect(')');
            }
            return new JfrPath.StatsOp(valuePath);
        } else if ("quantiles".equals(name) || "quantile".equals(name)) {
            List<Double> qs = new ArrayList<>();
            if (peek() == '(') {
                pos++; skipWs();
                boolean first = true;
                while (!eof() && peek() != ')') {
                    if (!first) { if (peek() == ',') { pos++; skipWs(); } }
                    first = false;
                    // accept either number or path spec prefixed by 'path='
                    if (startsWithIgnoreCase("path=")) {
                        pos += 5; // path=
                        valuePath = parsePathArg();
                    } else {
                        Object lit = parseLiteral();
                        if (!(lit instanceof Number)) throw error("quantiles() expects numeric quantiles 0..1");
                        qs.add(((Number) lit).doubleValue());
                    }
                    skipWs();
                }
                expect(')');
            }
            return new JfrPath.QuantilesOp(qs, valuePath);
        } else if ("sketch".equals(name)) {
            if (peek() == '(') {
                pos++; skipWs();
                if (peek() != ')') {
                    valuePath = parsePathArg();
                }
                expect(')');
            }
            return new JfrPath.SketchOp(valuePath);
        } else if ("len".equals(name)) {
            if (peek() == '(') {
                pos++; skipWs();
                if (peek() != ')') {
                    valuePath = parsePathArg();
                }
                expect(')');
            }
            return new JfrPath.LenOp(valuePath);
        } else {
            throw error("Unknown pipeline function: " + name);
        }
    }

    private List<String> parsePathArg() {
        List<String> fp = new ArrayList<>();
        while (!eof()) {
            String seg = readIdent();
            if (seg.isEmpty()) break;
            fp.add(seg);
            if (peek() == '/') { pos++; continue; }
            break;
        }
        if (fp.isEmpty()) throw error("Expected path");
        return fp;
    }

    private void expect(char c) {
        if (eof() || input.charAt(pos) != c) throw error("Expected '" + c + "'");
        pos++;
    }

    private int peek() { return eof() ? -1 : input.charAt(pos); }
    private boolean eof() { return pos >= input.length(); }
    private IllegalArgumentException error(String msg) { return new IllegalArgumentException(msg + " [at " + pos + "]"); }
}
