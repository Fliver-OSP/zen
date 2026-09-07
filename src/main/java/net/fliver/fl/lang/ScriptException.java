package net.fliver.fl.lang;

/**
 * A user-facing .fl error. Carries an optional stable error code plus the
 * source location (file and 1-based line) and the offending source line, so
 * hosts can render diagnostics like {@code api/hello.fl:12 [unknown-effect]}.
 *
 * <p>Location is set-if-unset: the innermost throw site wins and outer layers
 * only fill in what is missing. Callers attach context with the {@code at*}
 * helpers and rethrow the same instance.
 */
public final class ScriptException extends Exception {
  private String code;
  private String fileName;
  private int line = -1;
  private String snippet;

  public ScriptException(String message) {
    super(message);
  }

  public ScriptException(String message, Throwable cause) {
    super(message, cause);
  }

  public ScriptException(String code, String message) {
    super(message);
    this.code = code;
  }

  public ScriptException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** Stable machine-readable error code, or null when unclassified. */
  public String getCode() {
    return code;
  }

  public ScriptException withCode(String code) {
    if (this.code == null) this.code = code;
    return this;
  }

  /** Source file name, or null when unknown. */
  public String getFileName() {
    return fileName;
  }

  /** 1-based source line, or -1 when unknown. */
  public int getLine() {
    return line;
  }

  /** Offending source line text, or null when unknown. */
  public String getSnippet() {
    return snippet;
  }

  public ScriptException atLine(int line) {
    if (this.line < 0) this.line = line;
    return this;
  }

  public ScriptException atLocation(String fileName, int line) {
    if (this.fileName == null) this.fileName = fileName;
    return atLine(line);
  }

  public ScriptException withSnippet(String snippet) {
    if (this.snippet == null) this.snippet = snippet;
    return this;
  }

  public boolean hasLocation() {
    return line >= 0;
  }

  @Override
  public String getMessage() {
    String base = super.getMessage();
    if (base == null) base = "";
    StringBuilder suffix = new StringBuilder();
    if (fileName != null || line >= 0) {
      suffix.append(" (at ");
      suffix.append(fileName != null ? fileName : "<unknown>");
      if (line >= 0) suffix.append(":").append(line);
      suffix.append(")");
    }
    if (code != null) suffix.append(" [").append(code).append("]");
    if (suffix.length() == 0) return base;
    return base + suffix.toString();
  }
}
