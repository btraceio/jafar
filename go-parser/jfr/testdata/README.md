# Shared parser fixtures

The `node-pool-*.jfr` recordings are built by the Java test class
`parser-core/src/test/java/io/jafar/parser/impl/NodePoolFixture.java` (on
`SyntheticRecording`), so the Go and Java untyped parsers are tested against the
same bytes: `UnindexedPoolReferenceTest` in `parser-core` and the node-pool
tests in `../parser_test.go`. The layout and the four shapes are described in
`NodePoolFixture` and above `nodeRefs` in `../parser_test.go`.

Do not edit them by hand. `SharedFixturesTest` in `parser-core` fails when a
file here differs from what `NodePoolFixture` builds; to regenerate after
changing `NodePoolFixture` or `SyntheticRecording`, run from the repository root

```bash
JAFAR_WRITE_FIXTURES=1 ./gradlew :parser-core:test --tests io.jafar.parser.impl.SharedFixturesTest
```

and commit the result.
