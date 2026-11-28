# Shared parser fixtures

The `node-pool-*.jfr` recordings are built by the Java test class
`parser-core/src/test/java/io/jafar/parser/impl/NodePoolFixture.java` (on
`SyntheticRecording`), so the Go and Java untyped parsers are tested against the
same bytes: `UnindexedPoolReferenceTest` in `parser-core` and the node-pool
tests in `../parser_test.go`. The layout and the four shapes are described in
`NodePoolFixture` and above `nodeRefs` in `../parser_test.go`.

`start-time-shapes.jfr` is built the same way by
`parser-core/src/test/java/io/jafar/parser/impl/StartTimeShapesFixture.java`:
one event type per shape of `startTime` an event header must handle (after
other fields, an array, a pool reference, an `int`, no `Timestamp`, an
undeclared field type before it, a corrupt string before it). The event
headers tests read it on both sides: `UntypedHeaderHandlerTest` and
`CoreHeaderHandler8Test` in Java, `TestStartTimeShapes` and
`TestEventHeaderNextToATypeFilter` in `../header_test.go`.

Do not edit them by hand. `SharedFixturesTest` in `parser-core` fails when a
file here differs from what `NodePoolFixture` or `StartTimeShapesFixture`
builds; to regenerate after changing either or `SyntheticRecording`, run from
the repository root

```bash
JAFAR_WRITE_FIXTURES=1 ./gradlew :parser-core:test --tests io.jafar.parser.impl.SharedFixturesTest
```

and commit the result.
