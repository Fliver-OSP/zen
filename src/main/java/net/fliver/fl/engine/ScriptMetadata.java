package net.fliver.fl.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Optional per-request metadata (project slug, org slug, etc.) supplied by the host plugin. */
public interface ScriptMetadata {
  String get(String key);

  static ScriptMetadata empty() {
    return new MapMetadata(Collections.<String, String>emptyMap());
  }

  static ScriptMetadata of(String key, String value) {
    Map<String, String> map = new HashMap<String, String>();
    map.put(key, value == null ? "" : value);
    return new MapMetadata(map);
  }

  static ScriptMetadata fromMap(Map<String, String> map) {
    if (map == null || map.isEmpty()) {
      return empty();
    }
    return new MapMetadata(new HashMap<String, String>(map));
  }

  final class MapMetadata implements ScriptMetadata {
    private final Map<String, String> values;

    MapMetadata(Map<String, String> values) {
      this.values = values;
    }

    @Override
    public String get(String key) {
      if (key == null) return "";
      String v = values.get(key);
      return v == null ? "" : v;
    }
  }
}
