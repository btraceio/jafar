# Repository Guidelines

## Project Structure & Module Organization
- `parser/`: Core Java 21 library. Source in `src/main/java`, tests in `src/test/java`.
- `tools/`: Utilities built on the parser (e.g., `io.jafar.tools.Scrubber`).
- `demo/`: Sample CLI app (`application` plugin). Main class: `io.jafar.demo.Main`.
- `jafar-gradle-plugin/`: Gradle plugin (Groovy) that generates typed interfaces.
- Shared script: `get_resources.sh` (fetch test JFRs).
  - Gradle plugin is resolved via an included build (composite); no local publish needed for development.

## Build, Test, and Development Commands
- `./get_resources.sh` — download small JFR samples into `demo/src/test/resources`.
- `./gradlew build` — compile and run tests for included modules (`parser`, `tools`, `demo`).
- `./gradlew shadowJar` — produce fat jars (notably `parser` and `demo`).
- `./gradlew :demo:run --args="jafar /path/to/file.jfr"` — run the demo app.
- `./gradlew test` — run JUnit 5 tests (parser tests use higher heap; Java 21 toolchain).
- Plugin development: resolved from source via composite build; `tools` can apply `id 'io.btrace.jafar-gradle-plugin'` without publishing. Use `./gradlew publishToMavenLocal` if you need the parser artifact in your local Maven.

## Coding Style & Naming Conventions
- Language: Java 21 (parser/tools/demo), Groovy (plugin). Indent 4 spaces, no tabs; aim for 120 col width.
- Packages: `io.jafar.*`. Classes `PascalCase`, methods/fields `camelCase`, constants `UPPER_SNAKE_CASE`.
- Keep public API minimal; prefer package-private for internals. Use meaningful names and final where sensible.

### Pre-commit Formatting
- Spotless enforces formatting for Java, Groovy, and Gradle files.
- Git hook: `.githooks/pre-commit` runs `./gradlew spotlessApply` and restages changes.
- If hooks don’t run, set `git config core.hooksPath .githooks` once.

## Testing Guidelines
- Frameworks: JUnit Jupiter 5, Mockito. Place tests under `src/test/java` mirroring package paths.
- Name tests `*Test.java`; parameterized tests encouraged for edge cases; see existing fuzz/stability tests in `parser`.
- Run with `./gradlew test`. Large datasets may require ample heap (parser `test` task sets JVM args).

## Commit & Pull Request Guidelines
- Commits: concise, imperative mood; reference issues/PRs when relevant (e.g., “Fix parsing of constant pool (#17)”).
- PRs: include description, rationale, and test coverage or reproduction. Attach sample `.jfr` snippets if applicable.
- CI must pass. Before opening a PR, run `./gradlew test shadowJar` locally.

## Security & Configuration Tips
- Do not commit large recordings outside Git LFS. Avoid secrets in code; Sonatype credentials are provided via env/CI.
- The Gradle plugin is wired via included build; no local publish required during development.
