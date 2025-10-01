# Jafar Sample (Gradle)

Minimal standalone app showing how to use the Jafar parser outside the main repo.

## Prerequisites
- Java 21+
- Optional: `mavenLocal()` contains `io.btrace:jafar-parser` if you built locally

## Build & Run (Untyped)
- Run with the root wrapper from the repository root:

```
./gradlew -p examples/jafar-sample-gradle run --args="/path/to/recording.jfr"
```

This executes `com.acme.App`, which uses the untyped API and prints a line for each `jdk.ExecutionSample` event: `tid=<id> depth=<frames>`.

## Build & Run (Typed)
- Run the typed sample main:

```
./gradlew -p examples/jafar-sample-gradle runTyped --args="/path/to/recording.jfr"
```

This executes `com.acme.TypedApp`, which defines small annotated interfaces and prints the same info using the typed API.

## Dependency Source
- By default, `build.gradle` includes:
  - `mavenLocal()`
  - `mavenCentral()`
  - Sonatype snapshots
- If you have not published locally and a release is not on Maven Central:
  - Run `./gradlew :parser:publishToMavenLocal` at the repo root to publish `jafar-parser` to `mavenLocal`, or
  - Point the dependency version to a released version once available.

## Files
- `src/main/java/com/acme/App.java` — untyped example
- `src/main/java/com/acme/TypedApp.java` — typed example
- `build.gradle` — uses Java 21 toolchain and depends on `io.btrace:jafar-parser:0.0.1-SNAPSHOT`
