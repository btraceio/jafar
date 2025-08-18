package io.jafar.shell;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents an active JFR analysis session with a loaded recording.
 * Manages the parser, context, and provides statistics about the recording.
 */
public class JFRSession implements AutoCloseable {
    
    private final Path recordingPath;
    private final ParsingContext parsingContext;
    private TypedJafarParser parser;
    private final List<HandlerRegistration<?>> registrations = new ArrayList<>();
    private final Map<String, Long> eventTypeCounts = new HashMap<>();
    private final AtomicLong totalEvents = new AtomicLong(0);
    private final AtomicInteger handlerCount = new AtomicInteger(0);
    private boolean hasRun = false;
    
    public JFRSession(Path recordingPath, ParsingContext parsingContext) throws IOException {
        this.recordingPath = recordingPath;
        this.parsingContext = parsingContext;
        this.parser = parsingContext.newTypedParser(recordingPath);
        
        // Set up metadata collection to discover event types
        this.parser.withParserListener(new ChunkParserListener() {
            @Override
            public boolean onMetadata(io.jafar.parser.api.ParserContext context, MetadataEvent metadata) {
                // Collect all event types when metadata becomes available
                for (MetadataClass clazz : metadata.getClasses()) {
                    if (isEventType(clazz)) {
                        eventTypeCounts.putIfAbsent(clazz.getName(), 0L);
                    }
                }
                return true;
            }
        });
        
        // Do a quick metadata-only scan to populate available types immediately
        scanMetadata();
    }
    
    /**
     * Scan the recording file to collect metadata and populate available event types.
     * Uses StreamingChunkParser directly to efficiently get metadata and abort immediately.
     */
    private void scanMetadata() throws IOException {
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recordingPath, new ChunkParserListener() {
                @Override
                public boolean onMetadata(io.jafar.parser.api.ParserContext context, MetadataEvent metadata) {
                    for (MetadataClass clazz : metadata.getClasses()) {
                        if (isEventType(clazz)) {
                            eventTypeCounts.putIfAbsent(clazz.getName(), 0L);
                        }
                    }
                    // Return false to abort parsing immediately after metadata is collected
                    return false;
                }
            });
        } catch (Exception e) {
            // If metadata scanning fails, just continue without types - they'll be available after run()
            System.err.println("Warning: Failed to scan metadata: " + e.getMessage());
        }
    }
    
    /**
     * Register a handler for events of the specified type.
     * 
     * @param <T> the event type
     * @param eventClass the event class to handle
     * @param handler the handler function
     * @return the handler registration
     */
    public <T> HandlerRegistration<T> handle(Class<T> eventClass, JFRHandler<T> handler) {
        if (hasRun) {
            throw new IllegalStateException("Cannot register handlers after run() has been called");
        }
        
        // Wrap handler to count events
        JFRHandler<T> countingHandler = (event, ctl) -> {
            totalEvents.incrementAndGet();
            String typeName = eventClass.getSimpleName().replace("JFR", "").toLowerCase();
            // Try to find the actual JFR type name
            for (String key : eventTypeCounts.keySet()) {
                if (key.toLowerCase().contains(typeName) || typeName.contains(key.toLowerCase().replaceAll(".*\\.", ""))) {
                    eventTypeCounts.merge(key, 1L, Long::sum);
                    break;
                }
            }
            handler.handle(event, ctl);
        };
        
        HandlerRegistration<T> registration = parser.handle(eventClass, countingHandler);
        registrations.add(registration);
        handlerCount.incrementAndGet();
        return registration;
    }
    
    /**
     * Run the parser and invoke all registered handlers.
     * 
     * @throws IOException if parsing fails
     */
    public void run() throws IOException {
        if (hasRun) {
            throw new IllegalStateException("Session has already been run");
        }
        
        long startTime = System.currentTimeMillis();
        parser.run();
        long endTime = System.currentTimeMillis();
        hasRun = true;
        
        System.out.printf("Processed %d events in %d ms%n", totalEvents.get(), (endTime - startTime));
    }
    
    /**
     * Get available event types discovered in this recording.
     * 
     * @return set of event type names
     */
    public Set<String> getAvailableEventTypes() {
        return eventTypeCounts.keySet();
    }
    
    /**
     * Get the count of events processed for each type.
     * 
     * @return map of event type to count
     */
    public Map<String, Long> getEventTypeCounts() {
        return new HashMap<>(eventTypeCounts);
    }
    
    /**
     * Get total number of events processed.
     * 
     * @return total event count
     */
    public long getTotalEvents() {
        return totalEvents.get();
    }
    
    /**
     * Get number of registered handlers.
     * 
     * @return handler count
     */
    public int getHandlerCount() {
        return handlerCount.get();
    }
    
    /**
     * Get the recording file path.
     * 
     * @return recording path
     */
    public Path getRecordingPath() {
        return recordingPath;
    }
    
    /**
     * Get cumulative parsing time from the context.
     * 
     * @return uptime in nanoseconds
     */
    public long getUptime() {
        return parsingContext.uptime();
    }
    
    /**
     * Check if the session has been run.
     * 
     * @return true if run() has been called
     */
    public boolean hasRun() {
        return hasRun;
    }
    
    /**
     * Check if a metadata class represents a JFR event type.
     * Uses same logic as TypeDiscovery for consistency.
     * 
     * @param clazz metadata class to check
     * @return true if this is an event type
     */
    private boolean isEventType(MetadataClass clazz) {
        String name = clazz.getName();
        
        // Filter out internal types and focus on actual events
        if (name.contains("$") || name.contains("@")) {
            return false;
        }
        
        // Most JFR events are in jdk.* packages
        if (name.startsWith("jdk.") || name.startsWith("jfr.")) {
            return true;
        }
        
        // Custom events typically have meaningful package names
        if (name.contains(".") && !name.startsWith("java.")) {
            return true;
        }
        
        return false;
    }
    
    @Override
    public String toString() {
        return "JFRSession[" + recordingPath + "]";
    }
    
    @Override
    public void close() throws Exception {
        // Clean up all registrations
        for (HandlerRegistration<?> registration : registrations) {
            registration.destroy(parser);
        }
        registrations.clear();
        
        if (parser != null) {
            parser.close();
            parser = null;
        }
    }
}