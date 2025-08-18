#!/bin/bash

# Quick test of JFR shell functionality
echo "Starting JFR Shell test..."

# Test with some basic commands
echo -e "def x = 5 + 3\nx\n[1,2,3,4,5].sum()\nhelp\nexit" | java -jar /Users/jaroslav.bachorik/opensource/jafar/jfr-shell/build/libs/jfr-shell-0.0.1-SNAPSHOT.jar

echo ""
echo "Test completed!"