package io.jafar.shell;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.ParserContext;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    private final Set<String> metadataTypes = new HashSet<>();
    private final Set<String> allMetadataTypes = new HashSet<>();
    private final Set<String> nonPrimitiveMetadataTypes = new HashSet<>();
    private final Set<String> primitiveMetadataTypes = new HashSet<>();
    private java.util.List<Integer> cachedChunkIds;
    private Set<String> cpTypes; // lazily discovered
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
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                // Build index to resolve super types recursively
                Map<String, MetadataClass> byName = new HashMap<>();
                for (MetadataClass mc : metadata.getClasses()) byName.put(mc.getName(), mc);
                // Collect event and metadata types when metadata becomes available
                for (MetadataClass clazz : metadata.getClasses()) {
                    allMetadataTypes.add(clazz.getName());
                    if (!clazz.isPrimitive()) {
                        nonPrimitiveMetadataTypes.add(clazz.getName());
                    } else {
                        primitiveMetadataTypes.add(clazz.getName());
                    }
                    if (isEventType(clazz, byName)) {
                        eventTypeCounts.putIfAbsent(clazz.getName(), 0L);
                    }
                    if (isBrowsableMetadataType(clazz)) {
                        metadataTypes.add(clazz.getName());
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
                public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                    Map<String, MetadataClass> byName = new HashMap<>();
                    for (MetadataClass mc : metadata.getClasses()) byName.put(mc.getName(), mc);
                    for (MetadataClass clazz : metadata.getClasses()) {
                        allMetadataTypes.add(clazz.getName());
                        if (!clazz.isPrimitive()) {
                            nonPrimitiveMetadataTypes.add(clazz.getName());
                        } else {
                            primitiveMetadataTypes.add(clazz.getName());
                        }
                        if (isEventType(clazz, byName)) {
                            eventTypeCounts.putIfAbsent(clazz.getName(), 0L);
                        }
                        if (isBrowsableMetadataType(clazz)) {
                            metadataTypes.add(clazz.getName());
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

    /** Refresh discovered event types by rescanning metadata. */
    public synchronized void refreshTypes() throws IOException {
        eventTypeCounts.clear();
        metadataTypes.clear();
        allMetadataTypes.clear();
        nonPrimitiveMetadataTypes.clear();
        primitiveMetadataTypes.clear();
        cachedChunkIds = null;
        scanMetadata();
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

    /** Get available metadata (non-event) types discovered in this recording. */
    public Set<String> getAvailableMetadataTypes() {
        return Collections.unmodifiableSet(metadataTypes);
    }

    /** Get all metadata classes (events and non-events). */
    public Set<String> getAllMetadataTypes() {
        return Collections.unmodifiableSet(allMetadataTypes);
    }

    /** Get all non-primitive metadata class names. */
    public Set<String> getNonPrimitiveMetadataTypes() {
        return Collections.unmodifiableSet(nonPrimitiveMetadataTypes);
    }

    /** Get all primitive metadata class names. */
    public Set<String> getPrimitiveMetadataTypes() {
        return Collections.unmodifiableSet(primitiveMetadataTypes);
    }

    /** Get available chunk ids (indices) discovered by scanning headers). */
    public synchronized java.util.List<Integer> getAvailableChunkIds() {
        if (cachedChunkIds != null) return cachedChunkIds;
        java.util.List<Integer> out = new java.util.ArrayList<>();
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recordingPath, new ChunkParserListener() {
                @Override
                public boolean onChunkStart(ParserContext context, int chunkIndex, io.jafar.parser.internal_api.ChunkHeader header) {
                    out.add(chunkIndex);
                    return true;
                }
            });
        } catch (Exception ignore) {
        }
        cachedChunkIds = java.util.Collections.unmodifiableList(out);
        return cachedChunkIds;
    }

    /** Get available constant-pool types discovered in this recording (lazy scan). */
    public synchronized Set<String> getAvailableConstantPoolTypes() {
        if (cpTypes != null) return cpTypes;
        Set<String> out = new HashSet<>();
        try (StreamingChunkParser parser = new StreamingChunkParser(new UntypedParserContextFactory())) {
            parser.parse(recordingPath, new ChunkParserListener() {
                @Override
                public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
                    return true; // continue to checkpoint to load CPs
                }

                @Override
                public boolean onCheckpoint(ParserContext context, io.jafar.parser.internal_api.CheckpointEvent event) {
                    context.getConstantPools().pools().forEach(cp -> out.add(cp.getType().getName()));
                    return false; // stop after first checkpoint
                }
            });
        } catch (Exception ignore) {
            // Ignore discovery failures; fall back to empty set
        }
        cpTypes = out;
        return cpTypes;
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
    private boolean isEventType(MetadataClass clazz, Map<String, MetadataClass> resolver) {
        // Identify JFR events by following super type chain recursively
        HashSet<String> seen = new HashSet<>();
        String st = clazz.getSuperType();
        while (st != null && seen.add(st)) {
            if ("jdk.jfr.Event".equals(st)) return true;
            MetadataClass sup = resolver != null ? resolver.get(st) : null;
            st = (sup != null) ? sup.getSuperType() : null;
        }
        return false;
    }

    private boolean isBrowsableMetadataType(MetadataClass clazz) {
        if (clazz.isPrimitive()) return false;
        String st = clazz.getSuperType();
        if (st == null) return false;
        if ("java.lang.annotation.Annotation".equals(st)) return false;
        if ("jdk.jfr.SettingControl".equals(st)) return false;
        return true;
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
