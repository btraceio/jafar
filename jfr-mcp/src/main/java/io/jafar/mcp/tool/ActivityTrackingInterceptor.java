package io.jafar.mcp.tool;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * Decorates tool invocations with request accounting, activity timestamps, and last-resort handler
 * error mapping.
 */
public final class ActivityTrackingInterceptor implements ToolInterceptor {

  private final BooleanSupplier beginRequest;
  private final Runnable endRequest;
  private final Runnable touchActivity;
  private final Function<String, CallToolResult> errorResult;
  private final Logger logger;

  public ActivityTrackingInterceptor(
      BooleanSupplier beginRequest,
      Runnable endRequest,
      Runnable touchActivity,
      Function<String, CallToolResult> errorResult,
      Logger logger) {
    this.beginRequest = beginRequest;
    this.endRequest = endRequest;
    this.touchActivity = touchActivity;
    this.errorResult = errorResult;
    this.logger = logger;
  }

  @Override
  public McpServerFeatures.SyncToolSpecification apply(
      McpServerFeatures.SyncToolSpecification spec) {
    return new McpServerFeatures.SyncToolSpecification(
        spec.tool(),
        (exchange, args) -> {
          if (!beginRequest.getAsBoolean()) {
            return errorResult.apply("Server is shutting down");
          }
          try {
            touchActivity.run();
            return spec.callHandler().apply(exchange, args);
          } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) {
              throw vme;
            }
            logger.error("Uncaught exception in tool handler '{}'", spec.tool().name(), t);
            return errorResult.apply(
                "Internal error in " + spec.tool().name() + ": " + t.getClass().getSimpleName());
          } finally {
            endRequest.run();
            touchActivity.run();
          }
        });
  }
}
