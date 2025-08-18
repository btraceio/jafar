package io.jafar.shell;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import groovy.json.JsonBuilder;
import io.jafar.parser.api.ParsingContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Simple JFR Shell - Interactive Groovy-based JFR analysis environment
 */
public class SimpleJFRShell {
    
    private JFRSession currentSession;
    private final ParsingContext parsingContext = ParsingContext.create();
    private final GroovyShell groovyShell;
    private final BufferedReader reader;
    private boolean running = true;
    
    public SimpleJFRShell() {
        // Check if we have a real terminal
        if (System.console() != null) {
            this.reader = new BufferedReader(new InputStreamReader(System.in));
        } else {
            this.reader = new BufferedReader(new InputStreamReader(System.in));
        }
        
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
                System.out.print("jfr> ");
                System.out.flush();
                String input = reader.readLine();
                
                if (input == null) {
                    // EOF reached, exit gracefully
                    System.out.println();
                    System.out.println("Goodbye!");
                    running = false;
                    break;
                }
                
                input = input.trim();
                if (input.isEmpty()) {
                    continue;
                }
                
                if (handleCommand(input)) {
                    continue;
                }
                
                // Execute as Groovy code
                try {
                    Object result = groovyShell.evaluate(input);
                    if (result != null) {
                        System.out.println("=> " + result);
                    }
                } catch (Exception e) {
                    System.err.println("Error: " + e.getMessage());
                    if (System.getProperty("jfr.shell.debug") != null) {
                        e.printStackTrace();
                    }
                }
                
            } catch (IOException e) {
                System.err.println("IO Error: " + e.getMessage());
                running = false;
                break;
            }
        }
        
        cleanup();
    }
    
    private void displayBanner() {
        System.out.println("╔═══════════════════════════════════════╗");
        System.out.println("║           JFR Shell v1.0              ║");
        System.out.println("║   Interactive JFR Analysis Tool       ║");
        System.out.println("╚═══════════════════════════════════════╝");
        System.out.println();
        System.out.println("Type 'help' for available commands");
        System.out.println();
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
            System.err.println("File not found: " + recordingPath);
            return null;
        }
        
        // Close existing session
        if (currentSession != null) {
            try {
                currentSession.close();
            } catch (Exception e) {
                System.err.println("Error closing previous session: " + e.getMessage());
            }
        }
        
        try {
            currentSession = new JFRSession(recordingPath, parsingContext);
            System.out.println("Session opened: " + recordingPath);
            
            // Quick discovery of types
            TypeDiscovery discovery = new TypeDiscovery(parsingContext);
            Map<String, ?> types = discovery.discoverTypes(recordingPath);
            System.out.println("Found " + types.size() + " event types");
            
            // Update binding
            groovyShell.getContext().setVariable("session", currentSession);
            
            return currentSession;
        } catch (Exception e) {
            System.err.println("Failed to open recording: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    private void showHelp() {
        System.out.println("""
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
    }
    
    private void showSessionInfo() {
        if (currentSession == null) {
            System.out.println("No session open");
            return;
        }
        
        System.out.println("Session Information:");
        System.out.println("  Recording: " + currentSession.getRecordingPath());
        System.out.println("  Event Types: " + currentSession.getAvailableEventTypes().size());
        System.out.println("  Handlers: " + currentSession.getHandlerCount());
        System.out.println("  Has Run: " + currentSession.hasRun());
        if (currentSession.hasRun()) {
            System.out.println("  Total Events Processed: " + currentSession.getTotalEvents());
            System.out.println("  Uptime: " + (currentSession.getUptime() / 1_000_000) + "ms");
        }
    }
    
    private void showAvailableTypes() {
        if (currentSession == null) {
            System.out.println("No session open");
            return;
        }
        
        var types = currentSession.getAvailableEventTypes().stream().sorted().toList();
        System.out.println("Available Event Types (" + types.size() + "):");
        for (String type : types) {
            String simpleName = type.substring(type.lastIndexOf('.') + 1);
            String jfrClass = "JFR" + simpleName;
            System.out.println("  " + type + " -> " + jfrClass);
        }
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
            
            System.out.println("Data exported to: " + filename);
        } catch (Exception e) {
            System.err.println("Failed to export data: " + e.getMessage());
        }
    }
    
    private void closeSession() {
        if (currentSession != null) {
            try {
                currentSession.close();
                currentSession = null;
                groovyShell.getContext().setVariable("session", null);
                System.out.println("Session closed");
            } catch (Exception e) {
                System.err.println("Error closing session: " + e.getMessage());
            }
        } else {
            System.out.println("No session to close");
        }
    }
    
    private void cleanup() {
        if (currentSession != null) {
            try {
                currentSession.close();
            } catch (Exception e) {
                System.err.println("Error during cleanup: " + e.getMessage());
            }
        }
        System.out.println("Goodbye!");
    }
}