#!/bin/bash

# JFR Shell launcher script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$SCRIPT_DIR/jfr-shell/build/libs/jfr-shell.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "Building JFR Shell..."
    cd "$SCRIPT_DIR"
    ./gradlew :jfr-shell:shadowJar -q
fi

# Run with proper classpath and main class
java -cp "$JAR_PATH" io.jafar.shell.Main "$@"