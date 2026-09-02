package net.fliver.fl.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** RFC 4180 CSV reader/writer — no external dependencies (Java 8). */
public final class CsvParser {
  private CsvParser() {}

  /**
   * Parses CSV text into rows. First row is treated as headers when {@code
   * hasHeaderRow} is true; otherwise synthetic column names {@code col1},
   * {@code col2}, … are assigned.
   */
  public static CsvTable parse(String text, boolean hasHeaderRow) {
    if (text == null || text.isEmpty()) {
      return new CsvTable(Collections.<String>emptyList(), Collections.<List<String>>emptyList());
    }
    List<List<String>> rawRows = parseRows(text);
    if (rawRows.isEmpty()) {
      return new CsvTable(Collections.<String>emptyList(), Collections.<List<String>>emptyList());
    }

    List<String> headers;
    List<List<String>> dataRows;
    if (hasHeaderRow) {
      headers = normalizeHeaders(rawRows.get(0));
      dataRows = rawRows.size() > 1 ? rawRows.subList(1, rawRows.size()) : Collections.<List<String>>emptyList();
    } else {
      int width = rawRows.get(0).size();
      headers = syntheticHeaders(width);
      dataRows = rawRows;
    }
    return new CsvTable(headers, dataRows);
  }

  /** Serializes a table to CSV text (header row + data rows). */
  public static String serialize(CsvTable table) {
    if (table == null) return "";
    StringBuilder sb = new StringBuilder();
    if (!table.getHeaders().isEmpty()) {
      appendRow(sb, table.getHeaders());
    }
    for (List<String> row : table.getRawRows()) {
      if (sb.length() > 0) sb.append('\n');
      appendRow(sb, padRow(row, table.getHeaders().size()));
    }
    return sb.toString();
  }

  private static List<List<String>> parseRows(String text) {
    List<List<String>> rows = new ArrayList<List<String>>();
    List<String> current = new ArrayList<String>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            field.append('"');
            i += 2;
            continue;
          }
          inQuotes = false;
          i++;
          continue;
        }
        field.append(c);
        i++;
        continue;
      }
      if (c == '"') {
        inQuotes = true;
        i++;
        continue;
      }
      if (c == ',') {
        current.add(field.toString());
        field.setLength(0);
        i++;
        continue;
      }
      if (c == '\r') {
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        current.add(field.toString());
        field.setLength(0);
        rows.add(current);
        current = new ArrayList<String>();
        i++;
        continue;
      }
      if (c == '\n') {
        current.add(field.toString());
        field.setLength(0);
        rows.add(current);
        current = new ArrayList<String>();
        i++;
        continue;
      }
      field.append(c);
      i++;
    }
    if (field.length() > 0 || !current.isEmpty() || inQuotes) {
      current.add(field.toString());
      rows.add(current);
    }
    return rows;
  }

  private static void appendRow(StringBuilder sb, List<String> fields) {
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) sb.append(',');
      appendField(sb, fields.get(i));
    }
  }

  private static void appendField(StringBuilder sb, String value) {
    String v = value == null ? "" : value;
    boolean needsQuotes =
        v.indexOf(',') >= 0
            || v.indexOf('"') >= 0
            || v.indexOf('\n') >= 0
            || v.indexOf('\r') >= 0;
    if (!needsQuotes) {
      sb.append(v);
      return;
    }
    sb.append('"');
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      if (c == '"') sb.append('"');
      sb.append(c);
    }
    sb.append('"');
  }

  private static List<String> normalizeHeaders(List<String> raw) {
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < raw.size(); i++) {
      String h = raw.get(i) == null ? "" : raw.get(i).trim();
      if (h.isEmpty()) {
        h = "col" + (i + 1);
      }
      out.add(h);
    }
    return out;
  }

  private static List<String> syntheticHeaders(int width) {
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < width; i++) {
      out.add("col" + (i + 1));
    }
    return out;
  }

  private static List<String> padRow(List<String> row, int width) {
    List<String> out = new ArrayList<String>(row);
    while (out.size() < width) {
      out.add("");
    }
    if (out.size() > width) {
      return out.subList(0, width);
    }
    return out;
  }
}
