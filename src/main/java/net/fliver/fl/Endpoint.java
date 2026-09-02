package net.fliver.fl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A single {@code on fliver request "path":} declaration. Optional HTTP method
 * filters mirror Skript-style event options.
 */
public final class Endpoint {
  private final String path;
  private final String sourceFile;
  private final int line;
  private final List<String> body;
  private final Set<String> methods;

  public Endpoint(String path, String sourceFile, int line, List<String> body) {
    this(path, sourceFile, line, body, null);
  }

  public Endpoint(
      String path, String sourceFile, int line, List<String> body, Set<String> methods) {
    this.path = path;
    this.sourceFile = sourceFile;
    this.line = line;
    this.body = Collections.unmodifiableList(body);
    if (methods == null || methods.isEmpty()) {
      this.methods = Collections.emptySet();
    } else {
      Set<String> norm = new LinkedHashSet<String>();
      for (String m : methods) {
        if (m != null && !m.trim().isEmpty()) {
          norm.add(m.trim().toUpperCase(Locale.ROOT));
        }
      }
      this.methods = Collections.unmodifiableSet(norm);
    }
  }

  public String getPath() {
    return path;
  }

  public String getSourceFile() {
    return sourceFile;
  }

  public int getLine() {
    return line;
  }

  public List<String> getBody() {
    return body;
  }

  /** Empty = any method allowed. */
  public Set<String> getMethods() {
    return methods;
  }

  public boolean allowsMethod(String method) {
    if (methods.isEmpty()) return true;
    if (method == null || method.isEmpty()) return methods.contains("GET");
    return methods.contains(method.toUpperCase(Locale.ROOT));
  }
}
