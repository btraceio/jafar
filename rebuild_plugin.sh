#!/usr/bin/env bash

set -e

(
  cd jafar-gradle-plugin || exit
  ./gradlew clean publishToMavenLocal
)

./gradlew :parser:publishToMavenLocal