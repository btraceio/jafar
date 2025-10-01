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
                suggestOptions(line, candidates, new String[]{"--search", "--regex", "--refresh", "--events-only", "--non-events-only", "--primitives", "--summary"});
                break;
            case "show":
                // Suggest event types after 'events/' and metadata types after 'metadata/'
                if (sessions.getCurrent().isPresent()) {
                    String w = line.word();
                    if (w.startsWith("events/")) {
                        for (String t : sessions.getCurrent().get().session.getAvailableEventTypes()) {
                            candidates.add(new Candidate("events/" + t));
                        }
                    } else if (w.equals("events") || w.equals("events/")) {
                        candidates.add(noSpace("events/"));
                    } else if (w.startsWith("metadata/")) {
                        for (String t : sessions.getCurrent().get().session.getAllMetadataTypes()) {
                            candidates.add(new Candidate("metadata/" + t));
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
