#!/bin/bash

# JFR Shell Interactive Launcher
# This script provides an easy way to launch the JFR shell

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Starting JFR Shell..."
echo "==============================================="
echo ""

cd "$SCRIPT_DIR"

# Build the fat jar if it doesn't exist
JAR_FILE="jfr-shell/build/libs/jfr-shell-"*.jar
if ! compgen -G "$JAR_FILE" > /dev/null; then
    echo "Building jfr-shell fat jar..."
    ./gradlew :jfr-shell:shadowJar
fi

# Check if we have tty (interactive terminal)
if [ -t 0 ]; then
    echo "Launching interactive JFR shell..."
    exec java -jar jfr-shell/build/libs/jfr-shell-*.jar "$@"
else
    echo "No interactive terminal detected. Use: java -jar jfr-shell/build/libs/jfr-shell-*.jar"
    exit 1
fi