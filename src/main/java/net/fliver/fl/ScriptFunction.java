package net.fliver.fl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** User-defined .fl function (Skript-style), compiled from an indented body. */
public final class ScriptFunction {
  private final String name;
  private final List<String> parameters;
  private final List<String> body;
  private final List<Integer> bodyLineNumbers;
  private final String sourceFile;
  private final int line;

  public ScriptFunction(
      String name, List<String> parameters, List<String> body, String sourceFile, int line) {
    this(name, parameters, body, sourceFile, line, null);
  }

  /**
   * @param bodyLineNumbers 1-based file line per body entry, parallel to
   *     {@code body}; null or short lists fall back to body-relative numbers.
   */
  public ScriptFunction(
      String name,
      List<String> parameters,
      List<String> body,
      String sourceFile,
      int line,
      List<Integer> bodyLineNumbers) {
    this.name = name;
    this.parameters = Collections.unmodifiableList(parameters);
    this.body = Collections.unmodifiableList(body);
    this.bodyLineNumbers =
        bodyLineNumbers == null
            ? null
            : Collections.unmodifiableList(new ArrayList<Integer>(bodyLineNumbers));
    this.sourceFile = sourceFile;
    this.line = line;
  }

  public String getName() {
    return name;
  }

  public List<String> getParameters() {
    return parameters;
  }

  public List<String> getBody() {
    return body;
  }

  /**
   * 1-based file line per body entry, parallel to {@link #getBody()}.
   * Null when the function was built without line tracking.
   */
  public List<Integer> getBodyLineNumbers() {
    return bodyLineNumbers;
  }

  public String getSourceFile() {
    return sourceFile;
  }

  public int getLine() {
    return line;
  }
}
