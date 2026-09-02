package net.fliver.fl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of parsing one .fl file: endpoints, options, functions, diagnostics. */
public final class FlScript {
  private final String fileName;
  private final List<Endpoint> endpoints;
  private final List<ScriptFunction> functions;
  private final Map<String, String> options;
  private final List<String> errors;
  private final List<String> warnings;

  public FlScript(
      String fileName, List<Endpoint> endpoints, List<String> errors, List<String> warnings) {
    this(
        fileName,
        endpoints,
        Collections.<ScriptFunction>emptyList(),
        Collections.<String, String>emptyMap(),
        errors,
        warnings);
  }

  public FlScript(
      String fileName,
      List<Endpoint> endpoints,
      List<ScriptFunction> functions,
      Map<String, String> options,
      List<String> errors,
      List<String> warnings) {
    this.fileName = fileName;
    this.endpoints = Collections.unmodifiableList(endpoints);
    this.functions = Collections.unmodifiableList(functions);
    this.options =
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(options));
    this.errors = Collections.unmodifiableList(errors);
    this.warnings = Collections.unmodifiableList(warnings);
  }

  public String getFileName() {
    return fileName;
  }

  public List<Endpoint> getEndpoints() {
    return endpoints;
  }

  public List<ScriptFunction> getFunctions() {
    return functions;
  }

  public Map<String, String> getOptions() {
    return options;
  }

  public List<String> getErrors() {
    return errors;
  }

  public List<String> getWarnings() {
    return warnings;
  }
}
