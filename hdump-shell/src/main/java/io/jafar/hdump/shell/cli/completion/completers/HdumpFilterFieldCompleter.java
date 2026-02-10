package io.jafar.hdump.shell.cli.completion.completers;

import io.jafar.hdump.shell.cli.completion.HdumpMetadataService;
import io.jafar.shell.core.completion.CompletionContext;
import io.jafar.shell.core.completion.CompletionContextType;
import io.jafar.shell.core.completion.ContextCompleter;
import java.util.List;
import org.jline.reader.Candidate;

/**
 * Completer for field names inside filter predicates [...]. Suggests field names based on the
 * current root type (objects, classes, gcroots).
 */
public final class HdumpFilterFieldCompleter implements ContextCompleter<HdumpMetadataService> {

  @Override
  public boolean canHandle(CompletionContext ctx) {
    return ctx.type() == CompletionContextType.FILTER_FIELD;
  }

  @Override
  public void complete(
      CompletionContext ctx, HdumpMetadataService metadata, List<Candidate> candidates) {
    String partial = ctx.partialInput();
    String rootType = ctx.rootType();

    // Get fields for the current root type
    List<String> fields = metadata.getFieldsForRootType(rootType);

    for (String field : fields) {
      if (field.startsWith(partial)) {
        String description = getFieldDescription(rootType, field);
        candidates.add(candidate(field, field, description));
      }
    }
  }

  private String getFieldDescription(String rootType, String field) {
    if (rootType == null) return null;
    return switch (rootType.toLowerCase()) {
      case "objects" -> getObjectFieldDescription(field);
      case "classes" -> getClassFieldDescription(field);
      case "gcroots" -> getGcRootFieldDescription(field);
      default -> null;
    };
  }

  private String getObjectFieldDescription(String field) {
    return switch (field) {
      case "id" -> "object ID";
      case "class", "className" -> "class name";
      case "shallow", "shallowSize" -> "shallow size in bytes";
      case "retained", "retainedSize" -> "retained size in bytes";
      case "arrayLength" -> "array length";
      case "stringValue" -> "string content";
      default -> null;
    };
  }

  private String getClassFieldDescription(String field) {
    return switch (field) {
      case "id" -> "class ID";
      case "name" -> "fully qualified name";
      case "simpleName" -> "simple class name";
      case "instanceCount" -> "number of instances";
      case "instanceSize" -> "total instance size";
      case "superClass" -> "superclass name";
      case "isArray" -> "is array class";
      default -> null;
    };
  }

  private String getGcRootFieldDescription(String field) {
    return switch (field) {
      case "type" -> "GC root type";
      case "objectId" -> "referenced object ID";
      case "object" -> "object reference";
      case "threadSerial" -> "thread serial number";
      case "frameNumber" -> "stack frame number";
      default -> null;
    };
  }
}
