package net.fliver.fl.parse;

/** Localized parse error/warning messages for {@link net.fliver.fl.FlParser}. */
public interface ParseMessages {
  String get(String key, String... pairs);

  final class Defaults implements ParseMessages {
    @Override
    public String get(String key, String... pairs) {
      String template;
      switch (key) {
        case "parser.invalid-endpoint-name":
          template = "Line %s: invalid endpoint name \"%s\".";
          break;
        case "parser.duplicate-endpoint":
          template = "Line %s: duplicate endpoint \"%s\".";
          break;
        case "parser.unrecognized-line":
          template = "Line %s: unrecognized line.";
          break;
        case "parser.orphan-indent":
          template = "Line %s: indented line outside any block.";
          break;
        default:
          template = key;
      }
      if (pairs == null || pairs.length < 2) return template;
      String out = template;
      for (int i = 0; i + 1 < pairs.length; i += 2) {
        out = out.replace("%" + pairs[i] + "%", pairs[i + 1]);
      }
      return out;
    }
  }
}
