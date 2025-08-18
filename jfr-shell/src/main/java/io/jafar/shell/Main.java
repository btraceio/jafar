package io.jafar.shell;

import org.codehaus.groovy.tools.shell.IO;
import picocli.CommandLine;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * Main entry point for JFR Shell - Interactive JFR Analysis Tool
 */
@CommandLine.Command(
    name = "jfr-shell",
    description = "Interactive JFR Analysis Shell",
    version = "1.0.0",
    mixinStandardHelpOptions = true
)
public class Main implements Callable<Integer> {
    
    @CommandLine.Option(
        names = {"-f", "--file"},
        description = "JFR file to open immediately"
    )
    private String jfrFile;
    
    @CommandLine.Option(
        names = {"-s", "--script"},
        description = "Groovy script to execute and exit"
    )
    private String scriptFile;
    
    @CommandLine.Option(
        names = {"-e", "--execute"},
        description = "Groovy command to execute and exit"
    )
    private String executeCommand;
    
    @CommandLine.Option(
        names = {"--generate-types"},
        description = "Generate typed interfaces from JFR file and exit"
    )
    private boolean generateTypes;
    
    @CommandLine.Option(
        names = {"--types-package"},
        description = "Package name for generated types (default: io.jafar.shell.types)",
        defaultValue = "io.jafar.shell.types"
    )
    private String typesPackage;
    
    @CommandLine.Option(
        names = {"--types-output"},
        description = "Output directory for generated types (default: ./generated-types)",
        defaultValue = "./generated-types"
    )
    private String typesOutput;
    
    @CommandLine.Option(
        names = {"-q", "--quiet"},
        description = "Suppress banner and startup messages"
    )
    private boolean quiet;
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
    
    @Override
    public Integer call() throws Exception {
        // Handle type generation mode
        if (generateTypes) {
            return generateTypesAndExit();
        }
        
        // Handle script execution mode
        if (scriptFile != null || executeCommand != null) {
            return executeScriptAndExit();
        }
        
        // Interactive mode
        return startInteractiveShell();
    }
    
    private Integer generateTypesAndExit() throws Exception {
        if (jfrFile == null) {
            System.err.println("Error: --generate-types requires -f/--file to specify JFR recording");
            return 1;
        }
        
        Path jfrPath = Paths.get(jfrFile);
        if (!Files.exists(jfrPath)) {
            System.err.println("Error: JFR file not found: " + jfrFile);
            return 1;
        }
        
        Path outputDir = Paths.get(typesOutput);
        
        if (!quiet) {
            System.out.println("Generating types from: " + jfrFile);
            System.out.println("Output directory: " + outputDir);
            System.out.println("Package: " + typesPackage);
        }
        
        try {
            TypeDiscovery discovery = new TypeDiscovery(io.jafar.parser.api.ParsingContext.create());
            var discoveredTypes = discovery.discoverTypes(jfrPath);
            
            if (discoveredTypes.isEmpty()) {
                System.err.println("Warning: No event types discovered in recording");
                return 1;
            }
            
            discovery.generateTypedInterfaces(jfrPath, outputDir, typesPackage);
            
            if (!quiet) {
                System.out.println("Generated " + discoveredTypes.size() + " type interfaces:");
                discoveredTypes.keySet().stream().sorted().forEach(type -> {
                    String interfaceName = "JFR" + type.substring(type.lastIndexOf('.') + 1);
                    System.out.println("  " + type + " -> " + interfaceName + ".java");
                });
            }
            
            return 0;
        } catch (Exception e) {
            System.err.println("Error generating types: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }
    
    private Integer executeScriptAndExit() throws Exception {
        InteractiveJFRShell shell = new InteractiveJFRShell();
        
        try {
            // Open JFR file if specified
            if (jfrFile != null) {
                Path jfrPath = Paths.get(jfrFile);
                if (!Files.exists(jfrPath)) {
                    System.err.println("Error: JFR file not found: " + jfrFile);
                    return 1;
                }
                shell.openRecording(jfrPath);
            }
            
            // Execute script or command
            if (scriptFile != null) {
                Path scriptPath = Paths.get(scriptFile);
                if (!Files.exists(scriptPath)) {
                    System.err.println("Error: Script file not found: " + scriptFile);
                    return 1;
                }
                String script = Files.readString(scriptPath);
                System.out.println("Executing script: " + scriptFile);
                // For now, just print - would need to add script execution to SimpleJFRShell
            } else if (executeCommand != null) {
                System.out.println("Executing: " + executeCommand);
                // For now, just print the command - would need to add execution method
            }
            
            return 0;
        } catch (Exception e) {
            System.err.println("Error executing script: " + e.getMessage());
            if (!quiet) {
                e.printStackTrace();
            }
            return 1;
        } finally {
            // Cleanup handled by shell
        }
    }
    
    private Integer startInteractiveShell() throws Exception {
        // Create and configure shell
        InteractiveJFRShell shell = new InteractiveJFRShell();
        
        try {
            // Open JFR file if specified
            if (jfrFile != null) {
                Path jfrPath = Paths.get(jfrFile);
                if (!Files.exists(jfrPath)) {
                    System.err.println("Error: JFR file not found: " + jfrFile);
                    return 1;
                }
                shell.openRecording(jfrPath);
            }
            
            // Start interactive loop  
            if (!quiet) {
                System.out.println("JFR Shell started. Type 'help' for commands.");
                if (jfrFile != null) {
                    System.out.println("JFR file loaded. Type 'info' to see session details, 'types' to see available event types");
                }
                System.out.println();
            }
            
            shell.run();
            return 0;
            
        } catch (Exception e) {
            System.err.println("Error starting shell: " + e.getMessage());
            if (!quiet) {
                e.printStackTrace();
            }
            return 1;
        } finally {
            // Cleanup handled by shell
        }
    }
}