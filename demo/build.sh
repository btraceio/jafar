#!/bin/bash

(cd .. && ./gradlew publishToMavenLocal)
(cd ../jafar-gradle-plugin && ./gradlew --refresh-dependencies publishToMavenLocal)

./gradlew clean generateJafarTypes build --refresh-dependencies --info --stacktrace