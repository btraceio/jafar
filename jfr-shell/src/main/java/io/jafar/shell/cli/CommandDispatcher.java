package io.jafar.shell.cli;

import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.jfrpath.JfrPathEvaluator;
import io.jafar.shell.jfrpath.JfrPath;
import io.jafar.shell.jfrpath.JfrPathParser;
import io.jafar.shell.providers.MetadataProvider;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;

/**
 * Minimal command dispatcher for M1 session commands.
 */
public class CommandDispatcher {
    public interface IO {
        void println(String s);
        void printf(String fmt, Object... args);
        void error(String s);
    }

    public interface SessionChangeListener {
        void onCurrentSessionChanged(SessionManager.SessionRef current);
    }

    private final SessionManager sessions;
    private final IO io;
    private final SessionChangeListener listener;
    @FunctionalInterface
    public interface JfrSelector {
        List<java.util.Map<String, Object>> select(JFRSession session, String expr) throws Exception;
    }
    private final JfrSelector selector;

    public CommandDispatcher(SessionManager sessions, IO io, SessionChangeListener listener) {
        this(sessions, io, listener, null);
    }

    public CommandDispatcher(SessionManager sessions, IO io, SessionChangeListener listener, JfrSelector selector) {
        this.sessions = sessions;
        this.io = io;
        this.listener = listener;
        this.selector = selector;
    }

    public boolean dispatch(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return true;

        String cmd = parts[0].toLowerCase(Locale.ROOT);
        List<String> args = Arrays.asList(parts).subList(1, parts.length);

        try {
            switch (cmd) {
                case "open":
                    cmdOpen(args);
                    return true;
                case "sessions":
                    cmdSessions();
                    return true;
                case "use":
                    cmdUse(args);
                    return true;
                case "close":
                    cmdClose(args);
                    return true;
                case "info":
                    cmdInfo(args);
                    return true;
                case "show":
                    cmdShow(args, line);
                    return true;
                case "select":
                    io.println("Note: 'select' is deprecated. Use 'show' instead.");
                    cmdShow(args, line);
                    return true;
                case "help":
                    cmdHelp(args);
                    return true;
                case "metadata":
                    // Support: 'metadata class <name> [--tree|--json] [--fields] [--annotations]' and listing
                    if (!args.isEmpty() && "class".equalsIgnoreCase(args.get(0))) {
                        cmdMetadataClass(args.subList(1, args.size()));
                    } else {
                        cmdTypes(args);
                    }
                    return true;
                case "types":
                    io.println("Note: 'types' is deprecated. Use 'metadata' instead.");
                    cmdTypes(args);
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            io.error("Error: " + e.getMessage());
            return true;
        }
    }

    private void cmdOpen(List<String> args) throws Exception {
        if (args.isEmpty()) {
            io.error("Usage: open <path> [--alias NAME]");
            return;
        }
        String pathStr = args.get(0);
        String alias = null;
        if (args.size() >= 3 && "--alias".equals(args.get(1))) {
            alias = args.get(2);
        }

        Path path = Paths.get(pathStr);
        SessionManager.SessionRef ref = sessions.open(path, alias);
        io.println("Opened session #" + ref.id + (ref.alias != null ? " (" + ref.alias + ")" : "") + ": " + ref.session.getRecordingPath());
        listener.onCurrentSessionChanged(ref);
    }

    private void cmdSessions() {
        List<SessionManager.SessionRef> list = sessions.list();
        if (list.isEmpty()) {
            io.println("No sessions.");
            return;
        }
        Optional<SessionManager.SessionRef> current = sessions.current();
        for (SessionManager.SessionRef ref : list) {
            boolean isCurrent = current.isPresent() && current.get().id == ref.id;
            io.printf("%s#%d %s - %s%n", isCurrent ? "*" : " ", ref.id, ref.alias != null ? ref.alias : "", String.valueOf(ref.session.getRecordingPath()));
        }
    }

    private void cmdUse(List<String> args) {
        if (args.isEmpty()) {
            io.error("Usage: use <id|alias>");
            return;
        }
        String key = args.get(0);
        boolean ok = sessions.use(key);
        if (!ok) {
            io.error("No such session: " + key);
        } else {
            sessions.current().ifPresent(listener::onCurrentSessionChanged);
        }
    }

    private void cmdClose(List<String> args) throws Exception {
        if (args.isEmpty()) {
            // close current
            Optional<SessionManager.SessionRef> cur = sessions.current();
            if (cur.isEmpty()) {
                io.error("No session to close");
                return;
            }
            sessions.close(String.valueOf(cur.get().id));
            io.println("Closed session #" + cur.get().id);
        } else if (args.size() == 1 && "--all".equals(args.get(0))) {
            sessions.closeAll();
            io.println("Closed all sessions");
        } else {
            String key = args.get(0);
            boolean ok = sessions.close(key);
            if (!ok) io.error("No such session: " + key);
            else io.println("Closed session " + key);
        }
        sessions.current().ifPresent(listener::onCurrentSessionChanged);
    }

    private void cmdInfo(List<String> args) {
        Optional<SessionManager.SessionRef> ref;
        if (args.isEmpty()) {
            ref = sessions.current();
            if (ref.isEmpty()) {
                io.error("No session open");
                return;
            }
        } else {
            ref = sessions.get(args.get(0));
            if (ref.isEmpty()) {
                io.error("No such session: " + args.get(0));
                return;
            }
        }
        JFRSession s = ref.get().session;
        io.println("Session Information:");
        io.println("  Recording: " + s.getRecordingPath());
        io.println("  Event Types: " + s.getAvailableEventTypes().size());
        io.println("  Handlers: " + s.getHandlerCount());
        io.println("  Has Run: " + s.hasRun());
        if (s.hasRun()) {
            io.println("  Total Events Processed: " + s.getTotalEvents());
            io.println("  Uptime: " + (s.getUptime() / 1_000_000) + "ms");
        }
    }

    private void cmdShow(List<String> args, String fullLine) throws Exception {
        var cur = sessions.current();
        if (cur.isEmpty()) { io.error("No session open"); return; }
        // parse options: support --limit N, --format json, and metadata-only: --tree [--depth N]
        Integer limit = null;
        String format = null;
        boolean tree = false;
        Integer depth = null;
        JfrPath.MatchMode listMatchMode = JfrPath.MatchMode.ANY;
        List<String> tokens = args;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if ("--limit".equals(t) && i + 1 < tokens.size()) {
                limit = Integer.parseInt(tokens.get(i + 1));
                tokens = new java.util.ArrayList<>(tokens);
                tokens.remove(i + 1);
                tokens.remove(i);
                i -= 1;
                continue;
            }
            if ("--format".equals(t) && i + 1 < tokens.size()) {
                format = tokens.get(i + 1);
                tokens = new java.util.ArrayList<>(tokens);
                tokens.remove(i + 1);
                tokens.remove(i);
                i -= 1;
                continue;
            }
            if ("--tree".equals(t)) {
                tree = true;
                tokens = new java.util.ArrayList<>(tokens);
                tokens.remove(i);
                i -= 1;
                continue;
            }
            if ("--depth".equals(t) && i + 1 < tokens.size()) {
                try { depth = Integer.parseInt(tokens.get(i + 1)); }
                catch (NumberFormatException nfe) { io.error("Invalid --depth value: " + tokens.get(i + 1)); return; }
                tokens = new java.util.ArrayList<>(tokens);
                tokens.remove(i + 1);
                tokens.remove(i);
                i -= 1;
                continue;
            }
            if ("--list-match".equals(t) && i + 1 < tokens.size()) {
                String mm = tokens.get(i + 1).toLowerCase(java.util.Locale.ROOT);
                switch (mm) {
                    case "any" -> listMatchMode = JfrPath.MatchMode.ANY;
                    case "all" -> listMatchMode = JfrPath.MatchMode.ALL;
                    case "none" -> listMatchMode = JfrPath.MatchMode.NONE;
                    default -> { io.error("Invalid --list-match value: " + tokens.get(i + 1)); return; }
                }
                tokens = new java.util.ArrayList<>(tokens);
                tokens.remove(i + 1);
                tokens.remove(i);
                i -= 1;
                continue;
            }
        }
        if (tokens.isEmpty()) { io.error("Usage: show <expr> [--limit N] [--format json] [--tree] [--depth N] [--list-match any|all|none]"); return; }
        String expr = String.join(" ", tokens);
        // Evaluate
        if (selector != null) {
            List<java.util.Map<String, Object>> rows = selector.select(cur.get().session, expr);
            if (limit != null && limit < rows.size()) rows = rows.subList(0, limit);
            if ("json".equalsIgnoreCase(format)) {
                printJson(rows, io);
            } else {
                TableRenderer.render(rows, io);
            }
            return;
        }
        var q = JfrPathParser.parse(expr);
        var eval = new JfrPathEvaluator(listMatchMode);
        // If aggregation pipeline present, always evaluate as rows (preempts other handlers)
        if (q.pipeline != null && !q.pipeline.isEmpty()) {
            var rows = eval.evaluate(cur.get().session, q);
            if (limit != null && limit < rows.size()) rows = rows.subList(0, limit);
            if ("json".equalsIgnoreCase(format)) {
                printJson(rows, io);
            } else {
                TableRenderer.render(rows, io);
            }
            return;
        }

        // metadata tree rendering via --tree
        if (tree && q.root == JfrPath.Root.METADATA) {
            if (q.segments.isEmpty()) { io.error("--tree requires 'metadata/<type>' expression"); return; }
            String typeName = q.segments.get(0);
            int maxDepth = depth != null ? Math.max(0, depth) : 10;
            // If path targets a specific field, render field-focused tree
            if (q.segments.size() >= 2) {
                String seg1 = q.segments.get(1);
                String fieldName = null;
                if ("fields".equals(seg1) || "fieldsByName".equals(seg1)) {
                    if (q.segments.size() >= 3) {
                        fieldName = q.segments.get(2);
                    }
                } else if (seg1.startsWith("fields.")) {
                    fieldName = seg1.substring("fields.".length());
                } else if (seg1.startsWith("fieldsByName.")) {
                    fieldName = seg1.substring("fieldsByName.".length());
                }
                if (fieldName != null && !fieldName.isEmpty()) {
                    TreeRenderer.renderFieldRecursive(cur.get().session.getRecordingPath(), typeName, fieldName, io, maxDepth);
                    return;
                }
            }
            // Otherwise, render the class tree for the requested type
            TreeRenderer.renderMetadataRecursive(cur.get().session.getRecordingPath(), typeName, io, maxDepth);
            return;
        }
        if (q.root == JfrPath.Root.METADATA && q.segments.isEmpty()) {
            // Equivalent of 'types'
            cmdTypes(java.util.List.of());
            return;
        }
        if (q.root == JfrPath.Root.METADATA && q.segments.size() == 2) {
            String typeName = q.segments.get(0);
            String fieldName = q.segments.get(1);
            if ("fields".equals(fieldName) || "fieldsByName".equals(fieldName)) {
                // Delegate to generic evaluator to support 'fields' and 'fieldsByName'
                var values = eval.evaluateValues(cur.get().session, q);
                if (limit != null && limit < values.size()) values = values.subList(0, limit);
                if ("json".equalsIgnoreCase(format)) {
                    printJson(values, io);
                } else {
                    TableRenderer.renderValues(values, io);
                }
                return;
            }
            var evalMeta = new JfrPathEvaluator();
            Map<String, Object> fm = evalMeta.loadFieldMetadata(cur.get().session.getRecordingPath(), typeName, fieldName);
            if (fm == null) {
                io.println("(no rows)");
            } else {
                if ("json".equalsIgnoreCase(format)) {
                    printJson(fm, io);
                } else {
                    // Hide internal columns for field-level table view
                    java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(fm);
                    copy.remove("annotationsFull");
                    TableRenderer.render(java.util.List.of(copy), io);
                }
            }
            return;
        }
        if (q.segments.size() > 1) {
            var values = (q.root == JfrPath.Root.EVENTS)
                    ? eval.evaluateValuesWithLimit(cur.get().session, q, limit)
                    : eval.evaluateValues(cur.get().session, q);
            if (limit != null && limit < values.size()) values = values.subList(0, limit);
            if ("json".equalsIgnoreCase(format)) {
                printJson(values, io);
            } else {
                TableRenderer.renderValues(values, io);
            }
        } else {
            var rows = (q.root == JfrPath.Root.EVENTS)
                    ? eval.evaluateWithLimit(cur.get().session, q, limit)
                    : eval.evaluate(cur.get().session, q);
            if (limit != null && limit < rows.size()) rows = rows.subList(0, limit);
            if ("json".equalsIgnoreCase(format)) {
                printJson(rows, io);
            } else {
                // Hide internal columns for metadata tables (default table view)
                if (q.root == JfrPath.Root.METADATA && !rows.isEmpty()) {
                    java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(rows.get(0));
                    copy.remove("fieldsByName");
                    copy.remove("classAnnotations");
                    copy.remove("classAnnotationsFull");
                    copy.remove("settings");
                    copy.remove("settingsByName");
                    copy.remove("fieldCount");
                    rows = java.util.List.of(copy);
                }
                TableRenderer.render(rows, io);
            }
        }
    }

    private void cmdHelp(List<String> args) {
        if (args.isEmpty()) {
            io.println("help <command> - show help for a command (e.g., 'help show')");
            return;
        }
        String sub = args.get(0).toLowerCase(Locale.ROOT);
        if ("show".equals(sub) || "select".equals(sub)) {
            io.println("Usage: show <expr> [--limit N] [--format json] [--tree] [--depth N] [--list-match any|all|none]");
            io.println("Where <expr> is a JfrPath like:");
            io.println("  events/<type>[field/path op literal][...]");
            io.println("  metadata/<type>[/field][...]   (use --tree [--depth N] for recursive tree)");
            io.println("    Aliases for fields: fields.<name>, fieldsByName.<name>");
            io.println("  chunks[/field]");
            io.println("  cp[/<type>][/field]");
            io.println("Operators: = != > >= < <= ~ (regex)");
            io.println("Pipeline aggregations: append with '|':");
            io.println("  | count()                  → number of rows/events");
            io.println("  | stats([path])            → min,max,avg,stddev for numeric values");
            io.println("  | quantiles(q1,q2[,path=]) → pXX columns at requested quantiles");
            io.println("  | sketch([path])           → stats + p50,p90,p99");
            io.println("List match modes (arrays/lists): prefix a filter with any: | all: | none:");
            io.println("  e.g., [any:stackTrace/frames/method/name/string~\".*XXX.*\"]");
            io.println("Examples:");
            io.println("  show events/jdk.FileRead[bytes>=1000] --limit 5");
            io.println("  show events/jdk.ExecutionSample[thread/name~\"main\"] --limit 10");
            io.println("  show events/jdk.ExecutionSample[stackTrace/truncated=true]");
            io.println("  show events/jdk.ExecutionSample[any:stackTrace/frames/method/name/string~\".*XXX.*\"]");
            io.println("  show events/jdk.FileRead | count()");
            io.println("  show events/jdk.FileRead/bytes | stats()");
            io.println("  show events/jdk.FileRead/bytes | quantiles(0.5,0.9,0.99)");
            io.println("  show metadata/jdk.types.Method/name | count()");
            io.println("  show cp/jdk.types.Symbol | count()");
            io.println("  show events/jdk.SocketRead[remoteHost~\"10\\.0\\..*\"] --limit 3");
            io.println("  show metadata/jdk.Thread");
            io.println("  show metadata/jdk.Thread --format json");
            io.println("  show metadata/jdk.types.StackTrace --tree --depth 2");
            io.println("  show metadata/jdk.types.Method/fields/name --tree");
            io.println("  show metadata/jdk.types.Method/fields.name/annotations");
            io.println("  show metadata/jdk.types.Method/name");
            io.println("  show chunks/size");
            io.println("  show cp/name");
            io.println("  show cp/jdk.Thread/totalSize");
            return;
        }
        if ("metadata".equals(sub) || "types".equals(sub)) {
            io.println("Usage: metadata [--search <glob>|--regex <pattern>] [--refresh]");
            io.println("Lists available metadata types (all classes) in the current session.");
            io.println("Options:");
            io.println("  --search <glob>   Glob match on full type name (e.g., 'jdk.*')");
            io.println("  --regex  <pat>    Regex match on full type name");
            io.println("  --refresh         Rescan metadata to update types");
            io.println("  --events-only     Show only event types");
            io.println("  --non-events-only Show only non-event types");
            io.println("  --primitives      Show only primitive metadata types (debug)");
            io.println("  --summary         Print summary counts only");
            io.println("Examples:");
            io.println("  metadata");
            io.println("  metadata --search jdk.*");
            io.println("  metadata --regex ^custom\\..*");
            io.println("");
            io.println("Metadata class details:");
            io.println("  Usage: metadata class <name> [--tree|--json] [--fields] [--annotations] [--depth N]");
            io.println("  Flags:");
            io.println("    --tree         Hierarchical view (class → fields → annotations/settings); use --depth to limit");
            io.println("    --json         Full JSON with all properties");
            io.println("    --fields       Tabular list of fields (name, type, dimension, annotations)");
            io.println("    --annotations  Class-level annotations only");
            io.println("    --depth N      Max recursion depth (default 10)");
            io.println("  Examples:");
            io.println("    metadata class jdk.Thread");
            io.println("    metadata class jdk.Thread --tree --depth 3");
            io.println("    metadata class jdk.types.Method --fields");
            return;
        }
        io.println("No specific help for '" + sub + "'. Try 'help show'.");
    }

    // Very small JSON pretty-printer for Maps/Lists/values
    private static void printJson(Object obj, IO io) {
        io.println(toJson(obj, 0));
    }

    private static String toJson(Object obj, int indent) {
        String ind = "  ".repeat(indent);
        if (obj == null) return "null";
        if (obj instanceof String s) return '"' + escapeJson(s) + '"';
        if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
        if (obj instanceof java.util.Map<?,?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(ind).append("  ").append('"').append(escapeJson(String.valueOf(e.getKey()))).append('"').append(": ");
                sb.append(toJson(e.getValue(), indent + 1));
            }
            sb.append("\n").append(ind).append("}");
            return sb.toString();
        }
        if (obj instanceof java.util.Collection<?> coll) {
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            boolean first = true;
            for (Object v : coll) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(ind).append("  ").append(toJson(v, indent + 1));
            }
            sb.append("\n").append(ind).append("]");
            return sb.toString();
        }
        return '"' + escapeJson(String.valueOf(obj)) + '"';
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private void cmdTypes(List<String> args) {
        var cur = sessions.current();
        if (cur.isEmpty()) { io.error("No session open"); return; }
        boolean refresh = false;
        Boolean eventsOnly = null; // null=all, true=only events, false=only non-events
        boolean primitives = false;
        boolean summaryOnly = false;
        String search = null;
        boolean regex = false;
        for (int i=0; i<args.size(); i++) {
            String a = args.get(i);
            switch (a) {
                case "--refresh": refresh = true; break;
                case "--search": if (i+1<args.size()) { search = args.get(++i); } break;
                case "--regex": regex = true; break;
                case "--events-only": eventsOnly = Boolean.TRUE; break;
                case "--non-events-only": eventsOnly = Boolean.FALSE; break;
                case "--primitives": primitives = true; break;
                case "--summary": summaryOnly = true; break;
                default: if (search == null) search = a; // allow 'types pattern'
            }
        }
        try {
            if (refresh) {
                cur.get().session.refreshTypes();
            }
        } catch (Exception e) {
            io.error("Failed to refresh types: " + e.getMessage());
            return;
        }
        var sess = cur.get().session;
        java.util.Set<String> all = primitives ? sess.getPrimitiveMetadataTypes() : sess.getNonPrimitiveMetadataTypes();
        java.util.Set<String> events = sess.getAvailableEventTypes();
        java.util.List<String> types = new java.util.ArrayList<>();
        if (primitives) {
            // primitives: ignore eventsOnly flag and just show primitives
            types.addAll(all);
        } else if (eventsOnly == null) {
            types.addAll(all);
        } else if (eventsOnly.booleanValue()) {
            types.addAll(events);
        } else {
            for (String t : all) if (!events.contains(t)) types.add(t);
        }
        java.util.Collections.sort(types);
        if (search != null) {
            if (regex) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(search);
                types.removeIf(t -> !p.matcher(t).find());
            } else {
                java.nio.file.PathMatcher m = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + search);
                types.removeIf(t -> !m.matches(java.nio.file.Paths.get(t)));
            }
        }
        int total = types.size();
        String scopeLabel = primitives ? "primitives" : ((eventsOnly == null) ? "all" : (eventsOnly ? "events only" : "non-events only"));
        long eventsCnt = types.stream().filter(events::contains).count();
        long nonEventsCnt = total - eventsCnt;
        if (summaryOnly) {
            int primitivesCnt = sess.getPrimitiveMetadataTypes().size();
            int nonPrimitiveCnt = sess.getNonPrimitiveMetadataTypes().size();
            io.println("Types Summary:");
            io.println("  Non-primitive: " + nonPrimitiveCnt + " (events=" + events.size() + ", non-events=" + (nonPrimitiveCnt - events.size()) + ")");
            io.println("  Primitives:    " + primitivesCnt);
            io.println("  All metadata:  " + (nonPrimitiveCnt + primitivesCnt));
            return;
        }
        io.println("Available Types (" + total + ") [" + scopeLabel + "; events=" + eventsCnt + ", non-events=" + nonEventsCnt + "]:");
        for (String t : types) io.println("  " + t);
    }

    private void cmdMetadataClass(List<String> args) {
        var cur = sessions.current();
        if (cur.isEmpty()) { io.error("No session open"); return; }
        if (args.isEmpty()) { io.error("Usage: metadata class <name> [--tree|--json] [--fields] [--annotations]"); return; }
        String typeName = args.get(0);
        boolean tree = false;
        boolean json = false;
        boolean fields = false;
        boolean annotations = false;
        int maxDepth = 10;
        for (int i = 1; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--tree": tree = true; break;
                case "--json": json = true; break;
                case "--fields": fields = true; break;
                case "--annotations": annotations = true; break;
                case "--depth":
                    if (i + 1 < args.size()) {
                        try {
                            maxDepth = Math.max(0, Integer.parseInt(args.get(++i)));
                        } catch (NumberFormatException nfe) {
                            io.error("Invalid --depth value: " + args.get(i));
                            return;
                        }
                    } else {
                        io.error("--depth requires a number");
                        return;
                    }
                    break;
                default: io.error("Unknown option: " + args.get(i)); return;
            }
        }
        Map<String, Object> meta;
        try {
            meta = MetadataProvider.loadClass(cur.get().session.getRecordingPath(), typeName);
        } catch (Exception e) {
            io.error("Failed to load metadata: " + e.getMessage());
            return;
        }
        if (meta == null) { io.error("Type not found: " + typeName); return; }

        if (json) {
            printJson(meta, io);
            return;
        }
        if (tree) {
            // For --tree, render recursively starting from the requested type
            TreeRenderer.renderMetadataRecursive(cur.get().session.getRecordingPath(), typeName, io, maxDepth);
            return;
        }
        if (fields) {
            Object fbn = meta.get("fieldsByName");
            if (!(fbn instanceof Map<?,?> m) || m.isEmpty()) { io.println("(no fields)"); return; }
            java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Object k : m.keySet()) names.add(String.valueOf(k));
            java.util.Collections.sort(names);
            for (String fn : names) {
                Object v = m.get(fn);
                if (v instanceof Map<?,?> fm) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("name", fn);
                    row.put("type", fm.get("type"));
                    row.put("dimension", fm.get("dimension"));
                    Object a = fm.get("annotations");
                    row.put("annotations", (a instanceof java.util.List<?> l) ? String.join(" ", l.stream().map(String::valueOf).toList()) : "");
                    rows.add(row);
                }
            }
            TableRenderer.render(rows, io);
            return;
        }
        if (annotations) {
            Object ca = meta.get("classAnnotations");
            if (!(ca instanceof java.util.List<?> list) || list.isEmpty()) {
                io.println("(no annotations)");
            } else {
                io.println("Annotations:");
                for (Object v : list) io.println("  " + String.valueOf(v));
            }
            return;
        }
        // Default: show a one-row table with curated columns
        java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(meta);
        copy.remove("fieldsByName");
        copy.remove("classAnnotations");
        copy.remove("classAnnotationsFull");
        copy.remove("settingsByName");
        copy.remove("fieldCount");
        TableRenderer.render(java.util.List.of(copy), io);
    }
}
