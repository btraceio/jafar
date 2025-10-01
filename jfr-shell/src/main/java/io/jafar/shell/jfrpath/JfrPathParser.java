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
            if (peek() == '[') break;
            String seg = readUntil('/', '[');
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
        skipWs();
        if (!eof()) throw error("Trailing characters at position " + pos);
        return new Query(root, segments, preds);
    }

    private Predicate parsePredicate() {
        skipWs();
        List<String> fieldPath = new ArrayList<>();
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

    private void expect(char c) {
        if (eof() || input.charAt(pos) != c) throw error("Expected '" + c + "'");
        pos++;
    }

    private int peek() { return eof() ? -1 : input.charAt(pos); }
    private boolean eof() { return pos >= input.length(); }
    private IllegalArgumentException error(String msg) { return new IllegalArgumentException(msg + " [at " + pos + "]"); }
}

