#!/bin/bash

# JFR Shell Interactive Launcher
# This script provides an easy way to launch the JFR shell

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Starting JFR Shell..."
echo "==============================================="
echo ""
echo "To start the interactive shell, run:"
echo "  ./gradlew :jfr-shell:run --console=plain"
echo ""
echo "Or use this script for convenience:"
echo ""

cd "$SCRIPT_DIR"

# Check if we have tty (interactive terminal)
if [ -t 0 ]; then
    echo "Launching interactive JFR shell..."
    exec ./gradlew :jfr-shell:run --console=plain
else
    echo "No interactive terminal detected. Use: ./gradlew :jfr-shell:run --console=plain"
    exit 1
fi