package net.fliver.fl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches request paths against endpoint templates such as {@code bans/{player}}.
 * Literal segments are case-insensitive; {@code {name}} captures one path segment.
 */
public final class PathPattern {
  private static final Pattern PARAM = Pattern.compile("^\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}$");

  private PathPattern() {}

  public static boolean isValidTemplate(String path) {
    if (path == null || path.isEmpty() || path.length() > 120) return false;
    String[] parts = split(path);
    if (parts.length == 0) return false;
    for (String part : parts) {
      if (part.isEmpty()) return false;
      if (PARAM.matcher(part).matches()) continue;
      if (!part.matches("[a-zA-Z0-9_\\-]{1,64}")) return false;
    }
    return true;
  }

  public static Match match(String template, String requestPath) {
    String[] patternParts = split(template);
    String[] requestParts = split(requestPath);
    if (patternParts.length != requestParts.length) return null;
    Map<String, String> params = new LinkedHashMap<String, String>();
    int literals = 0;
    for (int i = 0; i < patternParts.length; i++) {
      String p = patternParts[i];
      String r = requestParts[i];
      Matcher m = PARAM.matcher(p);
      if (m.matches()) {
        params.put(m.group(1).toLowerCase(Locale.ROOT), r);
      } else if (p.equalsIgnoreCase(r)) {
        literals++;
      } else {
        return null;
      }
    }
    return new Match(template, params, literals, patternParts.length);
  }

  /** Prefer exact/more-literal templates over heavily parameterized ones. */
  public static int specificity(String template) {
    String[] parts = split(template);
    int literals = 0;
    for (String p : parts) {
      if (!PARAM.matcher(p).matches()) literals++;
    }
    return literals * 100 + parts.length;
  }

  public static boolean hasParams(String template) {
    for (String p : split(template)) {
      if (PARAM.matcher(p).matches()) return true;
    }
    return false;
  }

  public static List<String> paramNames(String template) {
    List<String> out = new ArrayList<String>();
    for (String p : split(template)) {
      Matcher m = PARAM.matcher(p);
      if (m.matches()) out.add(m.group(1).toLowerCase(Locale.ROOT));
    }
    return out;
  }

  private static String[] split(String path) {
    String t = path == null ? "" : path.trim();
    while (t.startsWith("/")) t = t.substring(1);
    while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
    if (t.isEmpty()) return new String[0];
    return t.split("/");
  }

  public static final class Match {
    private final String template;
    private final Map<String, String> params;
    private final int literalCount;
    private final int segmentCount;

    Match(String template, Map<String, String> params, int literalCount, int segmentCount) {
      this.template = template;
      this.params = Collections.unmodifiableMap(params);
      this.literalCount = literalCount;
      this.segmentCount = segmentCount;
    }

    public String getTemplate() {
      return template;
    }

    public Map<String, String> getParams() {
      return params;
    }

    public int getLiteralCount() {
      return literalCount;
    }

    public int getSegmentCount() {
      return segmentCount;
    }

    public int specificity() {
      return literalCount * 100 + segmentCount;
    }
  }
}
