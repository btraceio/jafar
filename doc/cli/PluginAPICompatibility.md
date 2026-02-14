# Backend Plugin API Compatibility Policy

## Semantic Versioning Contract

The backend plugin API (`io.jafar.shell.backend.*`) follows strict semantic versioning:

- **MAJOR** (1.0.0 → 2.0.0): Breaking changes to plugin API allowed
- **MINOR** (0.10.0 → 0.11.0): Backward-compatible additions only
- **PATCH** (0.10.0 → 0.10.1): Bug fixes, no API changes

## What Constitutes a Breaking Change

The following modifications require a MAJOR version bump:

1. **JfrBackend interface changes**:
   - Adding non-default methods
   - Removing methods
   - Changing method signatures (parameters, return types)

2. **BackendCapability enum**:
   - Removing capabilities
   - Changing capability semantics

3. **Source interfaces** (EventSource, MetadataSource, ChunkSource, ConstantPoolSource):
   - Changing method signatures
   - Modifying Map contract keys (e.g., removing "id" field)

4. **Exception contracts**:
   - Removing exception types
   - Changing exception inheritance

## Safe Changes (Minor/Patch)

These changes are backward-compatible:

1. Adding default methods to JfrBackend
2. Adding new BackendCapability enum values
3. Adding optional Map keys to returned data
4. Adding new exception types
5. Internal implementation changes (BackendRegistry, PluginManager)

## Automated Enforcement

japicmp runs on every build for non-SNAPSHOT versions:
- Compares current API against previous release
- Fails build on breaking changes (unless major version bump)
- Generates HTML report: `jfr-shell/build/reports/japicmp.html`

## For Plugin Developers

When creating a backend plugin:
- Depend on `jafar-shell` with `compileOnly` scope
- Target the minimum shell version you want to support
- Test against multiple shell versions if possible
- Use capability checks for optional features

## Version Requirements

Backend plugins should declare their compatible shell version range:
- **Minimum version**: Oldest shell version plugin supports
- **Maximum version**: Latest tested shell version (or next major version)
- Example: Plugin built for 0.10.0 works with 0.10.x and 0.11.x (until 1.0.0)

## Breaking Change Process

When a breaking change is necessary:

1. **Document the change**: Update this policy with migration guide
2. **Bump major version**: Increment MAJOR component (e.g., 0.x.y → 1.0.0)
3. **Deprecation period**: For minor versions, provide one version deprecation notice
4. **Update plugins**: Coordinate with known third-party plugin maintainers
5. **Release notes**: Clearly document breaking changes in CHANGELOG.md

## API Stability Timeline

- **0.10.0+**: Plugin API is stable, breaking changes require major bump
- **1.0.0**: Full API stability guarantee, follows semantic versioning strictly
