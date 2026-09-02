package net.fliver.fl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fliver.fl.parse.ParseMessages;

/**
 * Parses a single .fl file into endpoints, options and functions.
 *
 * <p>Skript-shaped indentation model; open-source engine (not a GPL Skript fork).
 */
public final class FlParser {
  private static final Pattern TRIGGER =
      Pattern.compile(
          "(?i)^on\\s+fliver\\s+request\\s+\"([^\"]*)\"(?:\\s+with\\s+methods?\\s+\"([^\"]*)\")?\\s*:$");
  private static final Pattern FUNCTION =
      Pattern.compile("(?i)^function\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)\\s*:$");
  private static final Pattern OPTIONS = Pattern.compile("(?i)^options\\s*:$");
  private static final Pattern OPTION_LINE =
      Pattern.compile("(?i)^([a-zA-Z_][a-zA-Z0-9_-]*)\\s*:\\s*(.+)$");
  private static final Pattern VALID_PATH = Pattern.compile("^[a-zA-Z0-9_\\-/{}]{1,120}$");

  private final ParseMessages messages;

  public FlParser(ParseMessages messages) {
    this.messages = messages == null ? new ParseMessages.Defaults() : messages;
  }

  public FlScript parse(String fileName, List<String> lines) {
    List<Endpoint> endpoints = new ArrayList<Endpoint>();
    List<ScriptFunction> functions = new ArrayList<ScriptFunction>();
    Map<String, String> options = new LinkedHashMap<String, String>();
    List<String> errors = new ArrayList<String>();
    List<String> warnings = new ArrayList<String>();
    Set<String> seenPaths = new HashSet<String>();
    Set<String> seenFunctions = new HashSet<String>();

    String currentPath = null;
    Set<String> currentMethods = null;
    int currentLine = -1;
    List<String> currentBody = null;
    boolean inOptions = false;
    String currentFunction = null;
    List<String> currentParams = null;
    int functionLine = -1;
    List<String> functionBody = null;

    for (int i = 0; i < lines.size(); i++) {
      int lineNumber = i + 1;
      String raw = lines.get(i);
      String trimmed = raw.trim();

      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }

      boolean indented = !raw.isEmpty() && Character.isWhitespace(raw.charAt(0));

      if (!indented) {
        closeOpen(
            endpoints,
            functions,
            fileName,
            currentPath,
            currentMethods,
            currentLine,
            currentBody,
            currentFunction,
            currentParams,
            functionLine,
            functionBody);
        currentPath = null;
        currentMethods = null;
        currentBody = null;
        currentFunction = null;
        currentParams = null;
        functionBody = null;
        inOptions = false;

        Matcher trigger = TRIGGER.matcher(trimmed);
        if (trigger.matches()) {
          String path = stripSlashes(trigger.group(1));
          if (looksLikeUrl(trigger.group(1))) {
            errors.add(
                "Line " + lineNumber
                    + ": endpoint path cannot be a URL or domain — use a relative path like \"api/hello\".");
            continue;
          }
          if (path.isEmpty()
              || !VALID_PATH.matcher(path).matches()
              || !PathPattern.isValidTemplate(path)) {
            errors.add(
                messages.get(
                    "parser.invalid-endpoint-name",
                    "line", String.valueOf(lineNumber),
                    "name", trigger.group(1)));
            continue;
          }
          if (!seenPaths.add(path)) {
            warnings.add(
                messages.get(
                    "parser.duplicate-endpoint",
                    "line", String.valueOf(lineNumber),
                    "name", path));
            continue;
          }
          currentPath = path;
          currentLine = lineNumber;
          currentBody = new ArrayList<String>();
          currentMethods = parseMethods(trigger.group(2));
          continue;
        }

        Matcher fn = FUNCTION.matcher(trimmed);
        if (fn.matches()) {
          String name = fn.group(1).toLowerCase(Locale.ROOT);
          if (!seenFunctions.add(name)) {
            warnings.add("Duplicate function \"" + name + "\" at line " + lineNumber);
            continue;
          }
          currentFunction = name;
          currentParams = parseParams(fn.group(2));
          functionLine = lineNumber;
          functionBody = new ArrayList<String>();
          continue;
        }

        if (OPTIONS.matcher(trimmed).matches()) {
          inOptions = true;
          continue;
        }

        errors.add(messages.get("parser.unrecognized-line", "line", String.valueOf(lineNumber)));
      } else {
        if (inOptions) {
          Matcher opt = OPTION_LINE.matcher(trimmed);
          if (opt.matches()) {
            options.put(opt.group(1).toLowerCase(Locale.ROOT), unquote(opt.group(2).trim()));
          } else {
            errors.add("Invalid option at line " + lineNumber);
          }
        } else if (currentFunction != null) {
          functionBody.add(raw.replace("\t", "    "));
        } else if (currentPath != null) {
          currentBody.add(raw.replace("\t", "    "));
        } else {
          errors.add(messages.get("parser.orphan-indent", "line", String.valueOf(lineNumber)));
        }
      }
    }

    closeOpen(
        endpoints,
        functions,
        fileName,
        currentPath,
        currentMethods,
        currentLine,
        currentBody,
        currentFunction,
        currentParams,
        functionLine,
        functionBody);

    return new FlScript(fileName, endpoints, functions, options, errors, warnings);
  }

  private static void closeOpen(
      List<Endpoint> endpoints,
      List<ScriptFunction> functions,
      String fileName,
      String currentPath,
      Set<String> currentMethods,
      int currentLine,
      List<String> currentBody,
      String currentFunction,
      List<String> currentParams,
      int functionLine,
      List<String> functionBody) {
    if (currentPath != null) {
      endpoints.add(
          new Endpoint(currentPath, fileName, currentLine, currentBody, currentMethods));
    }
    if (currentFunction != null) {
      functions.add(
          new ScriptFunction(
              currentFunction, currentParams, functionBody, fileName, functionLine));
    }
  }

  private static Set<String> parseMethods(String raw) {
    if (raw == null || raw.trim().isEmpty()) return null;
    Set<String> out = new LinkedHashSet<String>();
    String[] parts = raw.split("[,|/\\s]+");
    for (String p : parts) {
      if (!p.trim().isEmpty()) out.add(p.trim().toUpperCase(Locale.ROOT));
    }
    return out.isEmpty() ? null : out;
  }

  private static List<String> parseParams(String raw) {
    List<String> out = new ArrayList<String>();
    if (raw == null || raw.trim().isEmpty()) return out;
    for (String p : raw.split(",")) {
      String t = p.trim();
      if (t.startsWith("{") && t.endsWith("}") && t.length() >= 2) {
        t = t.substring(1, t.length() - 1);
      }
      if (!t.isEmpty()) out.add(t.toLowerCase(Locale.ROOT));
    }
    return out;
  }

  private static String unquote(String s) {
    if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  /**
   * Returns {@code true} when the raw path string looks like a URL or domain.
   * This is checked before VALID_PATH so the error message is more descriptive.
   */
  private static boolean looksLikeUrl(String raw) {
    if (raw == null) return false;
    String lower = raw.trim().toLowerCase(Locale.ROOT);
    if (lower.contains("://")) return true;
    if (lower.startsWith("http") || lower.startsWith("ftp")) return true;
    if (lower.matches(".*[a-z0-9]\\.[a-z]{2,}(/.*)?")) return true;
    if (lower.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*")) return true;
    return false;
  }

  private String stripSlashes(String value) {
    String result = value.trim();
    while (result.startsWith("/")) result = result.substring(1);
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }
}
