package io.jafar.mcp.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists JFR session mappings to disk so a restarted server can resume previously opened sessions
 * with the same IDs. Sessions survive MCP server restarts caused by Claude Code's half-close stdio
 * semantics.
 */
final class SessionPersistenceStore {

  private static final Logger LOG = LoggerFactory.getLogger(SessionPersistenceStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<Entry>> LIST_TYPE = new TypeReference<>() {};

  record Entry(int id, String path, String alias) {}

  private final Path storeFile;

  SessionPersistenceStore() {
    Path dir = Paths.get(System.getProperty("user.home"), ".jafar");
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      LOG.warn("Cannot create ~/.jafar dir: {}", e.getMessage());
    }
    this.storeFile = dir.resolve("mcp-sessions.json");
  }

  /** Loads all persisted session entries. Returns empty list on any error. */
  List<Entry> load() {
    if (!Files.exists(storeFile)) {
      return new ArrayList<>();
    }
    try {
      return MAPPER.readValue(storeFile.toFile(), LIST_TYPE);
    } catch (Exception e) {
      LOG.warn("Cannot load session store {}: {}", storeFile, e.getMessage());
      return new ArrayList<>();
    }
  }

  /** Adds or replaces an entry, then atomically writes the store. */
  synchronized void upsert(int id, String path, String alias) {
    List<Entry> entries = load();
    entries.removeIf(e -> e.id() == id);
    entries.add(new Entry(id, path, alias));
    write(entries);
  }

  /** Removes the entry for the given id, then atomically writes the store. */
  synchronized void remove(int id) {
    List<Entry> entries = load();
    entries.removeIf(e -> e.id() == id);
    write(entries);
  }

  /** Clears all entries. */
  synchronized void clear() {
    write(new ArrayList<>());
  }

  private void write(List<Entry> entries) {
    try {
      Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
      MAPPER.writeValue(tmp.toFile(), entries);
      Files.move(
          tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      LOG.warn("Cannot write session store {}: {}", storeFile, e.getMessage());
    }
  }
}
