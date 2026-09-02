package net.fliver.fl.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fliver.fl.util.Json;

/**
 * Runtime value used by the .fl engine. Mirrors Skript's loosely-typed model:
 * numbers, strings, booleans, lists and maps all flow through one box so
 * {@code set fliver response to ...} can serialize whatever the script built.
 */
public final class FlValue {
  public enum Kind {
    NULL,
    BOOLEAN,
    NUMBER,
    STRING,
    LIST,
    OBJECT
  }

  private final Kind kind;
  private final Object raw;

  private FlValue(Kind kind, Object raw) {
    this.kind = kind;
    this.raw = raw;
  }

  public static FlValue ofNull() {
    return new FlValue(Kind.NULL, null);
  }

  public static FlValue ofBoolean(boolean v) {
    return new FlValue(Kind.BOOLEAN, Boolean.valueOf(v));
  }

  public static FlValue ofNumber(double v) {
    return new FlValue(Kind.NUMBER, Double.valueOf(v));
  }

  public static FlValue ofLong(long v) {
    return new FlValue(Kind.NUMBER, Double.valueOf(v));
  }

  public static FlValue ofString(String v) {
    return new FlValue(Kind.STRING, v == null ? "" : v);
  }

  public static FlValue ofList(List<FlValue> values) {
    return new FlValue(Kind.LIST, new ArrayList<FlValue>(values));
  }

  public static FlValue ofStrings(Collection<String> values) {
    List<FlValue> list = new ArrayList<FlValue>();
    for (String s : values) {
      list.add(ofString(s));
    }
    return ofList(list);
  }

  public static FlValue ofObject(Map<String, FlValue> map) {
    return new FlValue(Kind.OBJECT, new LinkedHashMap<String, FlValue>(map));
  }

  public static FlValue fromJava(Object value) {
    if (value == null) return ofNull();
    if (value instanceof FlValue) return (FlValue) value;
    if (value instanceof Boolean) return ofBoolean((Boolean) value);
    if (value instanceof Number) return ofNumber(((Number) value).doubleValue());
    if (value instanceof String) return ofString((String) value);
    if (value instanceof List) {
      List<FlValue> out = new ArrayList<FlValue>();
      for (Object o : (List<?>) value) {
        out.add(fromJava(o));
      }
      return ofList(out);
    }
    if (value instanceof Map) {
      Map<String, FlValue> out = new LinkedHashMap<String, FlValue>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
        out.put(String.valueOf(e.getKey()), fromJava(e.getValue()));
      }
      return ofObject(out);
    }
    return ofString(String.valueOf(value));
  }

  public Kind getKind() {
    return kind;
  }

  public boolean isNull() {
    return kind == Kind.NULL;
  }

  public boolean asBoolean() {
    switch (kind) {
      case BOOLEAN:
        return ((Boolean) raw).booleanValue();
      case NUMBER:
        return ((Double) raw).doubleValue() != 0;
      case STRING:
        return ((String) raw).length() > 0;
      case LIST:
        return !((List<?>) raw).isEmpty();
      case OBJECT:
        return !((Map<?, ?>) raw).isEmpty();
      default:
        return false;
    }
  }

  public double asNumber() {
    switch (kind) {
      case NUMBER:
        return ((Double) raw).doubleValue();
      case BOOLEAN:
        return ((Boolean) raw).booleanValue() ? 1 : 0;
      case STRING:
        try {
          return Double.parseDouble(((String) raw).trim());
        } catch (NumberFormatException e) {
          return 0;
        }
      case LIST:
        return ((List<?>) raw).size();
      default:
        return 0;
    }
  }

  public long asLong() {
    return Math.round(asNumber());
  }

  public String asString() {
    switch (kind) {
      case NULL:
        return "";
      case STRING:
        return (String) raw;
      case BOOLEAN:
        return ((Boolean) raw).booleanValue() ? "true" : "false";
      case NUMBER:
        double d = ((Double) raw).doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
          return String.valueOf((long) d);
        }
        return String.valueOf(d);
      case LIST:
        {
          StringBuilder sb = new StringBuilder();
          List<?> list = (List<?>) raw;
          for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(((FlValue) list.get(i)).asString());
          }
          return sb.toString();
        }
      case OBJECT:
        return toJson();
      default:
        return String.valueOf(raw);
    }
  }

  @SuppressWarnings("unchecked")
  public List<FlValue> asList() {
    if (kind == Kind.LIST) {
      return Collections.unmodifiableList((List<FlValue>) raw);
    }
    if (kind == Kind.NULL) return Collections.emptyList();
    return Collections.singletonList(this);
  }

  @SuppressWarnings("unchecked")
  public Map<String, FlValue> asObject() {
    if (kind == Kind.OBJECT) {
      return (Map<String, FlValue>) raw;
    }
    return new LinkedHashMap<String, FlValue>();
  }

  public String toJson() {
    return writeJson(this);
  }

  private static String writeJson(FlValue v) {
    switch (v.kind) {
      case NULL:
        return "null";
      case BOOLEAN:
        return ((Boolean) v.raw).booleanValue() ? "true" : "false";
      case NUMBER:
        {
          double d = ((Double) v.raw).doubleValue();
          if (Double.isNaN(d) || Double.isInfinite(d)) {
            // NaN/Infinity have no JSON number representation (RFC 8259);
            // emit null instead of the invalid bare "NaN"/"Infinity" token.
            return "null";
          }
          if (d == Math.rint(d)) {
            return String.valueOf((long) d);
          }
          return String.valueOf(d);
        }
      case STRING:
        {
          String s = ((String) v.raw).trim();
          if (looksLikeJsonDocument(s)) {
            return s;
          }
          return "\"" + Json.escape(s) + "\"";
        }
      case LIST:
        {
          StringBuilder sb = new StringBuilder();
          sb.append('[');
          List<?> list = (List<?>) v.raw;
          for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(writeJson((FlValue) list.get(i)));
          }
          sb.append(']');
          return sb.toString();
        }
      case OBJECT:
        {
          StringBuilder sb = new StringBuilder();
          sb.append('{');
          Map<String, FlValue> map = v.asObject();
          boolean first = true;
          for (Map.Entry<String, FlValue> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(Json.escape(e.getKey())).append('"').append(':');
            sb.append(writeJson(e.getValue()));
          }
          sb.append('}');
          return sb.toString();
        }
      default:
        return "null";
    }
  }

  /** Pre-built JSON strings from builtins should pass through without re-quoting. */
  private static boolean looksLikeJsonDocument(String s) {
    if (s.isEmpty()) return false;
    char first = s.charAt(0);
    char last = s.charAt(s.length() - 1);
    if ((first == '{' && last == '}') || (first == '[' && last == ']')) {
      try {
        Json.parse(s);
        return true;
      } catch (RuntimeException ignored) {
        return false;
      }
    }
    return false;
  }

  @Override
  public String toString() {
    return asString();
  }

  public boolean equalsValue(FlValue other) {
    if (other == null) return false;
    if (kind == Kind.NUMBER || other.kind == Kind.NUMBER) {
      return Double.compare(asNumber(), other.asNumber()) == 0;
    }
    return asString().equalsIgnoreCase(other.asString());
  }

  public String typeName() {
    return kind.name().toLowerCase(Locale.ROOT);
  }
}
