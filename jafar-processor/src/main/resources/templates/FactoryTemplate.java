package {{PACKAGE}};

import io.jafar.parser.api.ConstantPools;
import io.jafar.parser.api.HandlerFactory;
import io.jafar.parser.api.MetadataLookup;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.metadata.MetadataClass;

/**
 * Generated factory for {@link {{HANDLER_NAME}}}.
 * Provides thread-local cached handler instances for reduced allocations.
 * JFR type: {{JFR_TYPE_NAME}}
 */
public final class {{FACTORY_NAME}} implements HandlerFactory<{{INTERFACE_NAME}}> {

  /** Thread-local cache for handler instances. */
  private final ThreadLocal<{{HANDLER_NAME}}> cache =
      ThreadLocal.withInitial({{HANDLER_NAME}}::new);

  /** Creates a new factory instance. */
  public {{FACTORY_NAME}}() {}

  /**
   * {@inheritDoc}
   */
  @Override
  public void bind(MetadataLookup metadata) {
    {{HANDLER_NAME}}.bind(metadata);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public {{INTERFACE_NAME}} get(RecordingStream stream, MetadataClass metadata, ConstantPools constantPools) {
    {{HANDLER_NAME}} handler = cache.get();
    handler.reset();
    try {
      handler.read(stream, metadata, constantPools);
    } catch (java.io.IOException e) {
      throw new RuntimeException("Failed to read event data", e);
    }
    return handler;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getJfrTypeName() {
    return "{{JFR_TYPE_NAME}}";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  @SuppressWarnings("unchecked")
  public Class<{{INTERFACE_NAME}}> getInterfaceClass() {
    return {{INTERFACE_NAME}}.class;
  }

}
