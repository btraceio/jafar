package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for GC root types after gcroots/. Suggests GC root types like THREAD_OBJ, JAVA_FRAME,
 * etc.
 */
public final class HdumpGcRootTypeCompleter implements ContextCompleter<HdumpMetadataService> {

  /** GC root types from the HPROF spec */
  private static final String[] GC_ROOT_TYPES = {
    "JNI_GLOBAL",
    "JNI_LOCAL",
    "JAVA_FRAME",
    "NATIVE_STACK",
    "STICKY_CLASS",
    "THREAD_BLOCK",
    "MONITOR_USED",
    "THREAD_OBJ"
  };

  @Override
  public boolean canHandle(CompletionContext ctx) {
    // Handle TYPE_PATTERN when rootType is gcroots
    return ctx.type() == CompletionContextType.TYPE_PATTERN && "gcroots".equals(ctx.rootType());
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput().toUpperCase();

    for (String type : GC_ROOT_TYPES) {
      if (type.startsWith(partial)) {
        String value = "gcroots/" + type;
        String description = getTypeDescription(type);
        candidates.add(new Candidate(value, type, null, description, null, null, false));
      }
    }
  }

  private String getTypeDescription(String type) {
    return switch (type) {
      case "JNI_GLOBAL" -> "JNI global reference";
      case "JNI_LOCAL" -> "JNI local reference";
      case "JAVA_FRAME" -> "Java stack frame local";
      case "NATIVE_STACK" -> "native stack reference";
      case "STICKY_CLASS" -> "system class";
      case "THREAD_BLOCK" -> "thread block reference";
      case "MONITOR_USED" -> "monitor (locked object)";
      case "THREAD_OBJ" -> "thread object";
      default -> null;
    };
  }
}
