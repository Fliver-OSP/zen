package net.fliver.fl;

import java.util.Collections;
import java.util.List;

/** User-defined .fl function (Skript-style), compiled from an indented body. */
public final class ScriptFunction {
  private final String name;
  private final List<String> parameters;
  private final List<String> body;
  private final String sourceFile;
  private final int line;

  public ScriptFunction(
      String name, List<String> parameters, List<String> body, String sourceFile, int line) {
    this.name = name;
    this.parameters = Collections.unmodifiableList(parameters);
    this.body = Collections.unmodifiableList(body);
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

  public String getSourceFile() {
    return sourceFile;
  }

  public int getLine() {
    return line;
  }
}
