package io.jafar.shell.cli;

import io.jafar.shell.core.SessionManager;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JLine completer for top-level shell commands and arguments (M1 scope).
 */
public class ShellCompleter implements Completer {
    private static final String[] COMMANDS = new String[]{
            "open", "sessions", "use", "close", "info", "show", "help", "metadata", "exit", "quit"
    };

    private final SessionManager sessions;
    private final org.jline.reader.impl.completer.FileNameCompleter fileCompleter = new org.jline.reader.impl.completer.FileNameCompleter();

    public ShellCompleter(SessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        List<String> words = line.words();
        int wordIndex = line.wordIndex();

        if (wordIndex == 0) {
            String cur = line.word().toLowerCase(Locale.ROOT);
            for (String c : COMMANDS) {
                if (c.startsWith(cur)) {
                    candidates.add(new Candidate(c));
                }
            }
            return;
        }

        String cmd = words.get(0).toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "help":
                // Suggest known subtopics
                candidates.add(new Candidate("show"));
                candidates.add(new Candidate("metadata"));
                break;
            case "open":
                // Suggest filenames and option names
                if (reader != null) {
                    fileCompleter.complete(reader, line, candidates);
                }
                suggestOptions(line, candidates, new String[]{"--alias"});
                break;
            case "metadata":
                // Support both listing and class subcommand completion
                if (words.size() >= 2 && "class".equalsIgnoreCase(words.get(1))) {
                    if (wordIndex == 2) {
                        // Suggest type names after 'metadata class '
                        if (sessions.getCurrent().isPresent()) {
                            String cur = line.word();
                            for (String t : sessions.getCurrent().get().session.getAllMetadataTypes()) {
                                if (t.startsWith(cur)) candidates.add(new Candidate(t));
                            }
                        }
                        break;
                    }
                    // After type name, suggest flags
                    suggestOptions(line, candidates, new String[]{"--tree", "--json", "--fields", "--annotations", "--depth"});
                } else {
                    // Top-level metadata listing options (including 'class')
                    suggestOptions(line, candidates, new String[]{"--search", "--regex", "--refresh", "--events-only", "--non-events-only", "--primitives", "--summary", "class"});
                }
                break;
            case "show":
                // Suggest event types after 'events/' and metadata types after 'metadata/'
                if (sessions.getCurrent().isPresent()) {
                    String w = line.word();
                    // Always suggest common show options
                    suggestOptions(line, candidates, new String[]{"--limit", "--format", "--tree", "--depth", "--list-match"});
                    // If completing the value of --list-match, suggest allowed values
                    List<String> wordsList = line.words();
                    if (wordsList.size() >= 2) {
                        String prev = wordsList.get(wordsList.size() - 2);
                        if ("--list-match".equals(prev)) {
                            for (String v : new String[]{"any", "all", "none"}) {
                                if (v.startsWith(w)) candidates.add(new Candidate(v));
                            }
                        }
                        // Pipeline function suggestions after a '|'
                        if ("|".equals(prev)) {
                            for (String v : new String[]{"count()", "stats()", "quantiles(0.5,0.9,0.99)", "sketch()"}) {
                                if (v.startsWith(w)) candidates.add(new Candidate(v));
                            }
                        }
                    }
                    // If current token starts with a pipe, suggest full "| fn()" forms
                    if (w.startsWith("|")) {
                        String after = w.substring(1).trim();
                        for (String v : new String[]{"| count()", "| stats()", "| quantiles(0.5,0.9,0.99)", "| sketch()"}) {
                            String cmp = v.substring(1).trim();
                            if (cmp.startsWith(after)) candidates.add(new Candidate(v));
                        }
                    }
                    // If typing inside a function token, suggest helpful templates
                    String lw = w.toLowerCase(Locale.ROOT);
                    // Try to infer current projection path for 'path=' suggestions
                    String inferredPath = null;
                    try {
                        // Find the expression token (contains root prefix)
                        for (String tok : wordsList) {
                            if (tok.startsWith("events/") || tok.startsWith("metadata/") || tok.startsWith("chunks/") || tok.startsWith("cp/")) {
                                String expr = tok;
                                // Strip filters if any
                                int b = expr.indexOf('[');
                                if (b >= 0) expr = expr.substring(0, b);
                                // For events root, projection starts after second '/'
                                if (expr.startsWith("events/")) {
                                    int s1 = expr.indexOf('/');
                                    int s2 = s1 < 0 ? -1 : expr.indexOf('/', s1 + 1);
                                    if (s2 > 0 && s2 + 1 < expr.length()) {
                                        inferredPath = expr.substring(s2 + 1);
                                    }
                                } else if (expr.startsWith("metadata/")) {
                                    int s1 = expr.indexOf('/');
                                    int s2 = s1 < 0 ? -1 : expr.indexOf('/', s1 + 1);
                                    if (s2 > 0 && s2 + 1 < expr.length()) {
                                        inferredPath = expr.substring(s2 + 1);
                                    }
                                }
                                break;
                            }
                        }
                    } catch (Exception ignore) {}
                    if (lw.startsWith("stats(")) {
                        if ("stats(".startsWith(lw) || lw.equals("stats(")) candidates.add(new Candidate("stats()"));
                        if (inferredPath != null && (lw.contains("path=") || lw.endsWith("("))) {
                            String sug = "stats(path=" + inferredPath + ")";
                            if (sug.toLowerCase(Locale.ROOT).startsWith(lw)) candidates.add(new Candidate(sug));
                        }
                    } else if (lw.startsWith("quant") || lw.startsWith("quantiles(")) {
                        for (String v : new String[]{"quantiles(0.5,0.9)", "quantiles(0.5,0.9,0.99)", "quantiles(0.5, path=)"}) {
                            if (v.toLowerCase(Locale.ROOT).startsWith(lw)) candidates.add(new Candidate(v));
                        }
                        if (inferredPath != null && (lw.contains("path=") || lw.endsWith("("))) {
                            String sug = "quantiles(0.5,0.9, path=" + inferredPath + ")";
                            if (sug.toLowerCase(Locale.ROOT).startsWith(lw)) candidates.add(new Candidate(sug));
                        }
                    } else if (lw.startsWith("sketch(")) {
                        if ("sketch(".startsWith(lw) || lw.equals("sketch(")) candidates.add(new Candidate("sketch()"));
                        if (inferredPath != null && (lw.contains("path=") || lw.endsWith("("))) {
                            String sug = "sketch(path=" + inferredPath + ")";
                            if (sug.toLowerCase(Locale.ROOT).startsWith(lw)) candidates.add(new Candidate(sug));
                        }
                    }
                    if (w.startsWith("events/")) {
                        for (String t : sessions.getCurrent().get().session.getAvailableEventTypes()) {
                            candidates.add(new Candidate("events/" + t));
                        }
                    } else if (w.equals("events") || w.equals("events/")) {
                        candidates.add(noSpace("events/"));
                    } else if (w.startsWith("metadata/")) {
                        // If it's exactly 'metadata/<type>/' or deeper, suggest JfrPath segments and fields
                        String prefix = "metadata/";
                        String rest = w.substring(prefix.length());
                        int slash = rest.indexOf('/');
                        if (slash < 0) {
                            for (String t : sessions.getCurrent().get().session.getAllMetadataTypes()) {
                                candidates.add(new Candidate("metadata/" + t));
                            }
                        } else {
                            String type = rest.substring(0, slash);
                            String after = rest.substring(slash + 1);
                            // Suggest known segments under metadata type
                            if (after.isEmpty()) {
                                candidates.add(new Candidate("metadata/" + type + "/fields"));
                                candidates.add(new Candidate("metadata/" + type + "/fieldsByName"));
                                candidates.add(new Candidate("metadata/" + type + "/superType"));
                                candidates.add(new Candidate("metadata/" + type + "/settings"));
                                candidates.add(new Candidate("metadata/" + type + "/classAnnotations"));
                            } else if (after.startsWith("fields/") || after.startsWith("fields.")) {
                                // Suggest field names or field subproperties depending on remainder
                                try {
                                    var meta = io.jafar.shell.providers.MetadataProvider.loadClass(
                                            sessions.getCurrent().get().session.getRecordingPath(), type);
                                    Object fbn = meta != null ? meta.get("fieldsByName") : null;
                                    if (fbn instanceof java.util.Map<?,?> m) {
                                        String rem = after.substring("fields".length());
                                        if (rem.startsWith("/")) rem = rem.substring(1);
                                        if (rem.startsWith(".")) rem = rem.substring(1);
                                        if (rem.isEmpty()) {
                                            // fields/ → list field names
                                            for (Object k : m.keySet()) {
                                                String fn = String.valueOf(k);
                                                String base = w.startsWith("metadata/" + type + "/fields/") ? "/" : ".";
                                                candidates.add(new Candidate("metadata/" + type + "/fields" + base + fn));
                                            }
                                        } else {
                                            // fields/<name>/ → list subproperties
                                            String fn = rem;
                                            int ix = fn.indexOf('/');
                                            if (ix < 0) ix = fn.indexOf('.');
                                            if (ix > 0) fn = fn.substring(0, ix);
                                            Object entry = m.get(fn);
                                            if (entry instanceof java.util.Map<?,?> em) {
                                                for (Object k : em.keySet()) {
                                                    String key = String.valueOf(k);
                                                    candidates.add(new Candidate("metadata/" + type + "/fields/" + fn + "/" + key));
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {}
                            } else if (after.contains("/fields/") || after.contains("/fields.")) {
                                // Suggest subproperties of a field
                                try {
                                    String fld = after.substring(after.indexOf("fields") + "fields".length() + 1);
                                    if (fld.startsWith("/")) fld = fld.substring(1);
                                    if (fld.startsWith(".")) fld = fld.substring(1);
                                    String fname = fld;
                                    int idx2 = fname.indexOf('/');
                                    if (idx2 < 0) idx2 = fname.indexOf('.');
                                    if (idx2 >= 0) fname = fname.substring(0, idx2);
                                    var meta2 = io.jafar.shell.providers.MetadataProvider.loadClass(
                                            sessions.getCurrent().get().session.getRecordingPath(), type);
                                    Object fbn2 = meta2 != null ? meta2.get("fieldsByName") : null;
                                    if (fbn2 instanceof java.util.Map<?,?> m2) {
                                        Object entry2 = m2.get(fname);
                                        if (entry2 instanceof java.util.Map<?,?> em2) {
                                            for (Object k2 : em2.keySet()) {
                                                String key2 = String.valueOf(k2);
                                                candidates.add(new Candidate("metadata/" + type + "/fields/" + fname + "/" + key2));
                                            }
                                        }
                                    }
                                } catch (Exception ignore) {}
                            }
                        }
                    } else if (w.equals("metadata") || w.equals("metadata/")) {
                        candidates.add(noSpace("metadata/"));
                    } else if (w.startsWith("cp/")) {
                        for (String t : sessions.getCurrent().get().session.getAvailableConstantPoolTypes()) {
                            candidates.add(new Candidate("cp/" + t));
                        }
                    } else if (w.equals("cp") || w.equals("cp/")) {
                        candidates.add(noSpace("cp/"));
                    } else if (w.startsWith("chunks/")) {
                        for (Integer id : sessions.getCurrent().get().session.getAvailableChunkIds()) {
                            candidates.add(noSpace("chunks/" + id));
                        }
                    } else if (w.equals("chunks") || w.equals("chunks/")) {
                        candidates.add(noSpace("chunks/"));
                    } else {
                        // Offer root options matching current token
                        if ("events/".startsWith(w)) candidates.add(noSpace("events/"));
                        if ("metadata/".startsWith(w)) candidates.add(noSpace("metadata/"));
                        if ("cp/".startsWith(w)) candidates.add(noSpace("cp/"));
                        if ("chunks/".startsWith(w)) candidates.add(noSpace("chunks/"));
                    }
                }
                break;
            case "use":
            case "info":
            case "close":
                // Suggest session ids and aliases
                List<Candidate> sess = new ArrayList<>();
                for (SessionManager.SessionRef ref : sessions.list()) {
                    sess.add(new Candidate(String.valueOf(ref.id)));
                    if (ref.alias != null) sess.add(new Candidate(ref.alias));
                }
                candidates.addAll(sess);
                if (cmd.equals("close")) {
                    candidates.add(new Candidate("--all"));
                }
                break;
            default:
                // No special args
        }
    }

    private static void suggestOptions(ParsedLine line, List<Candidate> candidates, String[] options) {
        String cur = line.word();
        for (String opt : options) {
            if (opt.startsWith(cur)) candidates.add(new Candidate(opt));
        }
    }

    private static Candidate noSpace(String value) {
        return new Candidate(value, value, null, null, null, null, false);
    }
}
