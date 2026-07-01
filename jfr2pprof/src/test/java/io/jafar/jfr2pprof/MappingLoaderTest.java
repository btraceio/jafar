package io.jafar.jfr2pprof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jafar.jfr2pprof.config.MappingConfig;
import io.jafar.jfr2pprof.config.MappingLoader;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class MappingLoaderTest {

  private static final String FULL_YAML =
      """
            frame:
              format: "{class}.{method}"
              includeLineNumbers: false

            profiles:
              - type: cpu-time
                event: datadog.ExecutionSample
                stackField: stackTrace
                values:
                  - name: cpu-time
                    unit: nanoseconds
                    field: weight
                labels:
                  - jfr: sampledThread.osThreadId
                    pprof: "thread id"
                  - jfr: sampledThread.javaName
                    pprof: "thread name"

              - type: allocation
                event: datadog.ObjectAllocationSample
                stackField: stackTrace
                values:
                  - { name: alloc-samples, unit: count, field: "@count" }
                  - { name: alloc-space,   unit: bytes, field: weight }
            """;

  @Test
  void testFullYaml() {
    MappingConfig config = MappingLoader.load(new StringReader(FULL_YAML));

    List<?> profiles = config.profiles();
    assertThat(profiles).hasSize(2);

    var p0 = config.profiles().get(0);
    assertThat(p0.event()).isEqualTo("datadog.ExecutionSample");
    assertThat(p0.values().get(0).name()).isEqualTo("cpu-time");
    assertThat(p0.values().get(0).unit()).isEqualTo("nanoseconds");
    assertThat(p0.labels().get(0).pprofKey()).isEqualTo("thread id");

    var p1 = config.profiles().get(1);
    assertThat(p1.values()).hasSize(2);

    // sampleTypeIndex: p0 has 1 value -> index 0; p1 starts at index 1
    assertThat(p0.sampleTypeIndex()).isEqualTo(0);
    assertThat(p1.sampleTypeIndex()).isEqualTo(1);

    // allValueTypes: 1 (cpu-time) + 2 (alloc-samples, alloc-space) = 3
    assertThat(config.allValueTypes()).hasSize(3);
  }

  @Test
  void testMissingEventField() {
    String yaml =
        """
                profiles:
                  - type: cpu-time
                    stackField: stackTrace
                    values:
                      - name: cpu-time
                        unit: nanoseconds
                        field: weight
                """;
    assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testEmptyProfilesList() {
    String yaml =
        """
                profiles: []
                """;
    assertThatThrownBy(() -> MappingLoader.load(new StringReader(yaml)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testCustomFrameFormat() {
    String yaml =
        """
                frame:
                  format: "{method}.{class}"
                  includeLineNumbers: false

                profiles:
                  - event: test.Event
                    values:
                      - name: cpu-time
                        unit: nanoseconds
                        field: weight
                """;
    MappingConfig config = MappingLoader.load(new StringReader(yaml));
    assertThat(config.frame().format()).isEqualTo("{method}.{class}");
  }
}
