package io.jafar.shell.llm.tuning;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import io.jafar.shell.llm.tuning.TestCase.Difficulty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** Loads and manages a collection of test cases for LLM prompt tuning. */
public class TestSuite {
  private final List<TestCase> testCases;
  private final String version;

  private TestSuite(String version, List<TestCase> testCases) {
    this.version = version;
    this.testCases = testCases;
  }

  /**
   * Loads a test suite from a JSON file.
   *
   * @param path path to the test suite JSON file
   * @return loaded test suite
   * @throws IOException if file cannot be read or parsed
   */
  public static TestSuite load(String path) throws IOException {
    return load(Path.of(path));
  }

  /**
   * Loads a test suite from a JSON file.
   *
   * @param path path to the test suite JSON file
   * @return loaded test suite
   * @throws IOException if file cannot be read or parsed
   */
  public static TestSuite load(Path path) throws IOException {
    String json = Files.readString(path);
    Gson gson = new Gson();
    TestSuiteData data = gson.fromJson(json, TestSuiteData.class);
    return new TestSuite(data.version, data.testCases);
  }

  public List<TestCase> getTestCases() {
    return testCases;
  }

  public String getVersion() {
    return version;
  }

  /**
   * Filters test cases by category.
   *
   * @param category the category to filter by
   * @return filtered list of test cases
   */
  public List<TestCase> byCategory(String category) {
    return testCases.stream()
        .filter(tc -> tc.category().equals(category))
        .collect(Collectors.toList());
  }

  /**
   * Filters test cases by difficulty level.
   *
   * @param difficulty the difficulty to filter by
   * @return filtered list of test cases
   */
  public List<TestCase> byDifficulty(Difficulty difficulty) {
    return testCases.stream()
        .filter(tc -> tc.difficulty() == difficulty)
        .collect(Collectors.toList());
  }

  /**
   * Filters test cases by keyword.
   *
   * @param keyword the keyword to filter by
   * @return filtered list of test cases
   */
  public List<TestCase> byKeyword(String keyword) {
    return testCases.stream()
        .filter(tc -> tc.keywords().contains(keyword))
        .collect(Collectors.toList());
  }

  /** Internal class for JSON deserialization. */
  private static class TestSuiteData {
    @SerializedName("version")
    String version;

    @SerializedName("testCases")
    List<TestCase> testCases;
  }
}
