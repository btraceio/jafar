package io.jafar.demo;

import io.jafar.tools.Scrubber.ScrubField;

import java.nio.file.Paths;

import static io.jafar.tools.Scrubber.scrubFile;

public final class Scrubber {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: Scrubber <jfr-to-scrub> <scrubbed-output>");
            System.exit(1);
        }

        scrubFile(Paths.get(args[0]), Paths.get(args[1]),
                clz -> {
                    if (clz.equals("jdk.InitialSystemProperty")) {
                        return new ScrubField("key", "value", (k, v) -> "java.home".equals(k));
                    }
                    return null; // no fields to scrub for other classes
                });
    }
}
