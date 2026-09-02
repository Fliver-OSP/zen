package net.fliver.fl.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fliver.fl.engine.FlValue;

/** In-memory CSV table: header names + raw string rows. */
public final class CsvTable {
  private final List<String> headers;
  private final List<List<String>> rawRows;

  public CsvTable(List<String> headers, List<List<String>> rawRows) {
    this.headers = Collections.unmodifiableList(new ArrayList<String>(headers));
    this.rawRows = Collections.unmodifiableList(copyRows(rawRows));
  }

  public List<String> getHeaders() {
    return headers;
  }

  public List<List<String>> getRawRows() {
    return rawRows;
  }

  public int rowCount() {
    return rawRows.size();
  }

  /** Each row as a case-insensitive-keyed map of column → cell value. */
  public List<Map<String, String>> asRowMaps() {
    List<Map<String, String>> out = new ArrayList<Map<String, String>>();
    for (List<String> row : rawRows) {
      out.add(rowToMap(row));
    }
    return out;
  }

  public Map<String, String> rowToMap(List<String> row) {
    Map<String, String> map = new LinkedHashMap<String, String>();
    for (int i = 0; i < headers.size(); i++) {
      String key = headers.get(i).toLowerCase();
      String val = i < row.size() && row.get(i) != null ? row.get(i) : "";
      map.put(key, val);
    }
    return map;
  }

  public List<FlValue> toFlValueRows() {
    List<FlValue> out = new ArrayList<FlValue>();
    for (Map<String, String> row : asRowMaps()) {
      Map<String, FlValue> obj = new LinkedHashMap<String, FlValue>();
      for (Map.Entry<String, String> e : row.entrySet()) {
        obj.put(e.getKey(), FlValue.ofString(e.getValue()));
      }
      out.add(FlValue.ofObject(obj));
    }
    return out;
  }

  public FlValue toFlValueHeaders() {
    return FlValue.ofStrings(headers);
  }

  /** Builds a table from a list of FlValue objects (each row must be OBJECT). */
  public static CsvTable fromFlValueRows(List<String> headers, List<FlValue> rows) {
    List<List<String>> raw = new ArrayList<List<String>>();
    for (FlValue row : rows) {
      raw.add(flValueToRow(headers, row));
    }
    return new CsvTable(headers, raw);
  }

  private static List<String> flValueToRow(List<String> headers, FlValue row) {
    Map<String, FlValue> obj = row.asObject();
    List<String> cells = new ArrayList<String>();
    for (String header : headers) {
      FlValue v = obj.get(header.toLowerCase());
      if (v == null) {
        for (Map.Entry<String, FlValue> e : obj.entrySet()) {
          if (e.getKey().equalsIgnoreCase(header)) {
            v = e.getValue();
            break;
          }
        }
      }
      cells.add(v == null || v.isNull() ? "" : v.asString());
    }
    return cells;
  }

  private static List<List<String>> copyRows(List<List<String>> rows) {
    List<List<String>> out = new ArrayList<List<String>>();
    for (List<String> row : rows) {
      out.add(new ArrayList<String>(row));
    }
    return out;
  }
}
