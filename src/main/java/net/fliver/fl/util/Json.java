package net.fliver.fl.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal hand-rolled JSON reader/writer helper. Deliberately not using
 * Gson: server jars across the 1.8.8-latest range bundle wildly different
 * (and sometimes relocated/shaded) versions of it, which is a well-known
 * source of NoSuchMethodError/ClassNotFoundException for plugins that
 * depend on "whatever Gson version the server happens to ship". This only
 * needs to handle the small, known shape of the Fliver API's responses.
 */
public final class Json {
  private Json() {}

  public static String escape(String value) {
    if (value == null) return "";
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }

  /** Parses a JSON document into nested Map/List/String/Long/Double/Boolean/null. */
  public static Object parse(String json) {
    if (json == null || json.trim().isEmpty()) {
      throw new IllegalArgumentException("empty JSON body");
    }
    Parser parser = new Parser(json);
    parser.skipWhitespace();
    Object value = parser.parseValue();
    return value;
  }

  private static final class Parser {
    private static final int MAX_DEPTH = 500;

    private final String s;
    private int pos;
    private int depth;

    Parser(String s) {
      this.s = s;
    }

    void skipWhitespace() {
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    char peek() {
      if (pos >= s.length()) throw new IllegalStateException("Unexpected end of JSON");
      return s.charAt(pos);
    }

    Object parseValue() {
      skipWhitespace();
      char c = peek();
      switch (c) {
        case '{':
          return parseObject();
        case '[':
          return parseArray();
        case '"':
          return parseString();
        case 't':
          expect("true");
          return Boolean.TRUE;
        case 'f':
          expect("false");
          return Boolean.FALSE;
        case 'n':
          expect("null");
          return null;
        default:
          return parseNumber();
      }
    }

    void expect(String literal) {
      if (pos + literal.length() > s.length() || !s.startsWith(literal, pos)) {
        throw new IllegalStateException("Invalid JSON literal at " + pos);
      }
      pos += literal.length();
    }

    Map<String, Object> parseObject() {
      if (++depth > MAX_DEPTH) {
        throw new IllegalStateException("JSON nesting too deep (max " + MAX_DEPTH + ")");
      }
      try {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
          pos++;
          return map;
        }
        while (true) {
          skipWhitespace();
          String key = parseString();
          skipWhitespace();
          if (peek() != ':') throw new IllegalStateException("Expected ':' at " + pos);
          pos++;
          Object value = parseValue();
          map.put(key, value);
          skipWhitespace();
          char c = peek();
          if (c == ',') {
            pos++;
            continue;
          }
          if (c == '}') {
            pos++;
            break;
          }
          throw new IllegalStateException("Expected ',' or '}' at " + pos);
        }
        return map;
      } finally {
        depth--;
      }
    }

    List<Object> parseArray() {
      if (++depth > MAX_DEPTH) {
        throw new IllegalStateException("JSON nesting too deep (max " + MAX_DEPTH + ")");
      }
      try {
        List<Object> list = new ArrayList<Object>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
          pos++;
          return list;
        }
        while (true) {
          list.add(parseValue());
          skipWhitespace();
          char c = peek();
          if (c == ',') {
            pos++;
            continue;
          }
          if (c == ']') {
            pos++;
            break;
          }
          throw new IllegalStateException("Expected ',' or ']' at " + pos);
        }
        return list;
      } finally {
        depth--;
      }
    }

    String parseString() {
      if (peek() != '"') throw new IllegalStateException("Expected string at " + pos);
      pos++;
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (pos >= s.length()) throw new IllegalStateException("Unterminated string");
        char c = s.charAt(pos++);
        if (c == '"') break;
        if (c == '\\') {
          if (pos >= s.length()) throw new IllegalStateException("Unterminated escape");
          char esc = s.charAt(pos++);
          switch (esc) {
            case '"':
              sb.append('"');
              break;
            case '\\':
              sb.append('\\');
              break;
            case '/':
              sb.append('/');
              break;
            case 'n':
              sb.append('\n');
              break;
            case 'r':
              sb.append('\r');
              break;
            case 't':
              sb.append('\t');
              break;
            case 'b':
              sb.append('\b');
              break;
            case 'f':
              sb.append('\f');
              break;
            case 'u':
              if (pos + 4 > s.length()) throw new IllegalStateException("Invalid unicode escape");
              String hex = s.substring(pos, pos + 4);
              sb.append((char) Integer.parseInt(hex, 16));
              pos += 4;
              break;
            default:
              throw new IllegalStateException("Invalid escape at " + pos);
          }
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }

    Object parseNumber() {
      int start = pos;
      if (peek() == '-') pos++;
      while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
      boolean isDouble = false;
      if (pos < s.length() && s.charAt(pos) == '.') {
        isDouble = true;
        pos++;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
      }
      if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
        isDouble = true;
        pos++;
        if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
      }
      String num = s.substring(start, pos);
      if (num.isEmpty() || "-".equals(num)) {
        throw new IllegalStateException("Invalid number at " + start);
      }
      if (isDouble) {
        double d = Double.parseDouble(num);
        if (Double.isInfinite(d)) {
          throw new IllegalStateException("Number out of range at " + start);
        }
        return Double.valueOf(d);
      }
      return Long.valueOf(num);
    }
  }
}
