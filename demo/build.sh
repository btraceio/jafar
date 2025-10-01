#!/bin/bash

(cd .. && ./gradlew publishToMavenLocal)
(cd ../jafar-gradle-plugin && ./gradlew publishToMavenLocal)

./gradlew build
