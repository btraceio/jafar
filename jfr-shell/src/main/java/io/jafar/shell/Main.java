package io.jafar.shell;

import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "jfr-shell", description = "Interactive JFR CLI", version = "0.1.0", mixinStandardHelpOptions = true)
public class Main implements Callable<Integer> {
    @CommandLine.Option(names = {"-f", "--file"}, description = "JFR file to open immediately")
    private String jfrFile;

    @CommandLine.Option(names = {"-q", "--quiet"}, description = "Suppress banner")
    private boolean quiet;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try (Shell shell = new Shell()) {
            if (jfrFile != null) {
                Path p = Paths.get(jfrFile);
                if (!Files.exists(p)) {
                    System.err.println("Error: JFR file not found: " + jfrFile);
                    return 1;
                }
                shell.openIfPresent(p);
            }
            shell.run();
            return 0;
        }
    }
}
