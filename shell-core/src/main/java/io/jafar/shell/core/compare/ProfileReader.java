package io.jafar.shell.core.compare;

import java.nio.file.Path;
import java.util.List;

/**
 * Reads one profile file into what a comparison needs. One per format; a shell module offers its
 * reader through {@link io.jafar.shell.core.ShellModule#profileReader()}.
 */
public interface ProfileReader {

  /** The format this reader reads: {@code jfr}, {@code pprof}. */
  String format();

  /** The sample types {@code file} offers, in file order. */
  List<SampleTypeInfo> sampleTypes(Path file) throws Exception;

  /**
   * Reads {@code file}.
   *
   * @throws IllegalArgumentException when {@code selection} names a sample type the file does not
   *     have
   */
  RecordingProfile read(Path file, ProfileSelection selection) throws Exception;
}
