package io.jafar.shell;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import groovy.json.JsonBuilder;
import io.jafar.parser.api.ParsingContext;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Interactive JFR Shell using JLine for proper terminal handling
 */
public class InteractiveJFRShell {
    
    private JFRSession currentSession;
    private final ParsingContext parsingContext = ParsingContext.create();
    private final GroovyShell groovyShell;
    private final LineReader lineReader;
    private final Terminal terminal;
    private boolean running = true;
    
    public InteractiveJFRShell() throws IOException {
        // Set up JLine terminal and reader
        this.terminal = TerminalBuilder.builder()
            .system(true)
            .build();
            
        this.lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .build();
        
        // Set up Groovy shell with binding
        Binding binding = new Binding();
        setupBinding(binding);
        this.groovyShell = new GroovyShell(binding);
        
        // Initialize imports
        groovyShell.evaluate("import io.jafar.parser.api.*");
        groovyShell.evaluate("import io.jafar.shell.types.*");
        groovyShell.evaluate("import java.util.concurrent.atomic.*");
        groovyShell.evaluate("import java.util.concurrent.ConcurrentHashMap");
        groovyShell.evaluate("import groovy.transform.Field");
    }
    
    public void run() {
        displayBanner();
        
        while (running) {
            try {
                String input = lineReader.readLine("jfr> ");
                
                if (input == null || input.trim().isEmpty()) {
                    continue;
                }
                
                input = input.trim();
                
                if (handleCommand(input)) {
                    continue;
                }
                
                // Execute as Groovy code
                try {
                    Object result = groovyShell.evaluate(input);
                    if (result != null) {
                        terminal.writer().println("=> " + result);
                        terminal.flush();
                    }
                } catch (Exception e) {
                    terminal.writer().println("Error: " + e.getMessage());
                    if (System.getProperty("jfr.shell.debug") != null) {
                        e.printStackTrace(terminal.writer());
                    }
                    terminal.flush();
                }
                
            } catch (UserInterruptException e) {
                // Ctrl+C pressed
                terminal.writer().println("^C");
                terminal.flush();
            } catch (EndOfFileException e) {
                // Ctrl+D pressed or EOF
                terminal.writer().println();
                terminal.writer().println("Goodbye!");
                terminal.flush();
                running = false;
            } catch (Exception e) {
                terminal.writer().println("Unexpected error: " + e.getMessage());
                terminal.flush();
                running = false;
            }
        }
        
        cleanup();
    }
    
    private void displayBanner() {
        terminal.writer().println("╔═══════════════════════════════════════╗");
        terminal.writer().println("║           JFR Shell v1.0              ║");
        terminal.writer().println("║   Interactive JFR Analysis Tool       ║");
        terminal.writer().println("╚═══════════════════════════════════════╝");
        terminal.writer().println();
        terminal.writer().println("Type 'help' for available commands, 'exit' to quit");
        terminal.writer().println();
        terminal.flush();
    }
    
    private boolean handleCommand(String input) {
        String[] parts = input.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        
        switch (command) {
            case "help":
                showHelp();
                return true;
            case "info":
                showSessionInfo();
                return true;
            case "types":
                showAvailableTypes();
                return true;
            case "close":
                closeSession();
                return true;
            case "exit", "quit":
                terminal.writer().println("Goodbye!");
                terminal.flush();
                running = false;
                return true;
            default:
                return false; // Not a command, execute as Groovy
        }
    }
    
    private void setupBinding(Binding binding) {
        binding.setVariable("shell", this);
        binding.setVariable("ctx", parsingContext);
        
        // Helper functions as closures
        binding.setVariable("open", new Closure<JFRSession>(this) {
            public JFRSession doCall(String path) {
                return openRecording(Paths.get(path));
            }
        });
        
        binding.setVariable("handle", new Closure<Object>(this) {
            @SuppressWarnings("unchecked")
            public Object doCall(Class<?> eventType, Closure<?> handler) {
                if (currentSession == null) {
                    throw new IllegalStateException("No session open. Use 'open(path)' first");
                }
                return currentSession.handle((Class<Object>) eventType, (event, ctl) -> {
                    handler.call(event, ctl);
                });
            }
        });
        
        binding.setVariable("run", new Closure<Object>(this) {
            public Object doCall() {
                if (currentSession == null) {
                    throw new IllegalStateException("No session open. Use 'open(path)' first");
                }
                try {
                    currentSession.run();
                    return null;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        
        binding.setVariable("export", new Closure<Object>(this) {
            public Object doCall(Object data, String filename) {
                exportData(data, filename);
                return null;
            }
        });
    }
    
    public JFRSession openRecording(Path recordingPath) {
        if (!Files.exists(recordingPath)) {
            terminal.writer().println("File not found: " + recordingPath);
            terminal.flush();
            return null;
        }
        
        // Close existing session
        if (currentSession != null) {
            try {
                currentSession.close();
            } catch (Exception e) {
                terminal.writer().println("Error closing previous session: " + e.getMessage());
                terminal.flush();
            }
        }
        
        try {
            terminal.writer().println("Opening recording: " + recordingPath);
            
            // Create session - this will collect metadata via listener
            currentSession = new JFRSession(recordingPath, parsingContext);
            terminal.writer().println("Session created successfully");
            
            // Show session's metadata collection results
            var sessionTypes = currentSession.getAvailableEventTypes();
            terminal.writer().println("Found " + sessionTypes.size() + " event types");
            
            if (sessionTypes.size() > 0) {
                terminal.writer().println("Available event types:");
                sessionTypes.stream()
                    .sorted()
                    .limit(5)
                    .forEach(type -> {
                        String simpleName = type.substring(type.lastIndexOf('.') + 1);
                        String jfrClass = "JFR" + simpleName;
                        terminal.writer().println("  - " + type + " -> " + jfrClass);
                    });
                    
                if (sessionTypes.size() > 5) {
                    terminal.writer().println("  ... and " + (sessionTypes.size() - 5) + " more");
                }
            }
            
            terminal.flush();
            
            // Update binding
            groovyShell.getContext().setVariable("session", currentSession);
            
            return currentSession;
        } catch (Exception e) {
            terminal.writer().println("Failed to open recording: " + e.getMessage());
            if (System.getProperty("jfr.shell.debug") != null) {
                e.printStackTrace(terminal.writer());
            }
            terminal.flush();
            return null;
        }
    }
    
    private void showHelp() {
        terminal.writer().println("""
JFR Shell Commands
==================

Commands:
  help                     - Show this help
  info                     - Show session information
  types                    - Show available event types
  close                    - Close current session
  exit/quit               - Exit shell

Functions:
  open(path)              - Open JFR recording
  handle(Type, {})        - Register event handler
  run()                   - Process recording
  export(data, filename)  - Export data to file

Examples:
  open("recording.jfr")
  def count = 0
  handle(JFRExecutionSample) { event, ctl -> count++ }
  run()
  println "Processed ${count} samples"
  
  def threadStats = [:]
  handle(JFRExecutionSample) { event, ctl ->
    def threadId = event.sampledThread().javaThreadId()
    threadStats[threadId] = (threadStats[threadId] ?: 0) + 1
  }
  run()
  println threadStats.sort { -it.value }.take(5)
""");
        terminal.flush();
    }
    
    private void showSessionInfo() {
        if (currentSession == null) {
            terminal.writer().println("No session open");
            terminal.flush();
            return;
        }
        
        terminal.writer().println("Session Information:");
        terminal.writer().println("  Recording: " + currentSession.getRecordingPath());
        terminal.writer().println("  Event Types: " + currentSession.getAvailableEventTypes().size());
        terminal.writer().println("  Handlers: " + currentSession.getHandlerCount());
        terminal.writer().println("  Has Run: " + currentSession.hasRun());
        if (currentSession.hasRun()) {
            terminal.writer().println("  Total Events Processed: " + currentSession.getTotalEvents());
            terminal.writer().println("  Uptime: " + (currentSession.getUptime() / 1_000_000) + "ms");
        }
        terminal.flush();
    }
    
    private void showAvailableTypes() {
        if (currentSession == null) {
            terminal.writer().println("No session open");
            terminal.flush();
            return;
        }
        
        var types = currentSession.getAvailableEventTypes().stream().sorted().toList();
        terminal.writer().println("Available Event Types (" + types.size() + "):");
        for (String type : types) {
            String simpleName = type.substring(type.lastIndexOf('.') + 1);
            String jfrClass = "JFR" + simpleName;
            terminal.writer().println("  " + type + " -> " + jfrClass);
        }
        terminal.flush();
    }
    
    private void exportData(Object data, String filename) {
        try {
            String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
            
            switch (extension) {
                case "json":
                    JsonBuilder json = new JsonBuilder(data);
                    Files.write(Paths.get(filename), json.toPrettyString().getBytes());
                    break;
                default:
                    // Default to text format
                    Files.write(Paths.get(filename), data.toString().getBytes());
            }
            
            terminal.writer().println("Data exported to: " + filename);
            terminal.flush();
        } catch (Exception e) {
            terminal.writer().println("Failed to export data: " + e.getMessage());
            terminal.flush();
        }
    }
    
    private void closeSession() {
        if (currentSession != null) {
            try {
                currentSession.close();
                currentSession = null;
                groovyShell.getContext().setVariable("session", null);
                terminal.writer().println("Session closed");
            } catch (Exception e) {
                terminal.writer().println("Error closing session: " + e.getMessage());
            }
        } else {
            terminal.writer().println("No session to close");
        }
        terminal.flush();
    }
    
    private void cleanup() {
        if (currentSession != null) {
            try {
                currentSession.close();
            } catch (Exception e) {
                terminal.writer().println("Error during cleanup: " + e.getMessage());
            }
        }
        
        try {
            terminal.close();
        } catch (IOException e) {
            // Ignore
        }
    }
}