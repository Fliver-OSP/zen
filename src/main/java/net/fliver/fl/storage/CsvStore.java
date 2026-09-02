package net.fliver.fl.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.fliver.fl.engine.FlValue;
import net.fliver.fl.lang.ScriptException;

/** Persistent CSV files under the plugin data folder. */
public final class CsvStore {
  public static final int MAX_ROWS = 10_000;
  public static final int MAX_BYTES = 1_000_000;

  private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

  private static volatile CsvStore instance;

  private final File folder;
  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<String, Object>();

  private CsvStore(File folder) {
    this.folder = folder;
    if (!folder.exists() && !folder.mkdirs()) {
      throw new IllegalStateException("Could not create CSV folder: " + folder);
    }
  }

  public static void init(File csvFolder) {
    instance = new CsvStore(csvFolder);
  }

  public static CsvStore get() throws ScriptException {
    CsvStore store = instance;
    if (store == null) {
      throw new ScriptException("CSV storage is not initialized.");
    }
    return store;
  }

  public void create(String name, List<String> headers) throws ScriptException, IOException {
    validateName(name);
    if (headers == null || headers.isEmpty()) {
      throw new ScriptException("CSV headers cannot be empty.");
    }
    List<String> normalized = normalizeHeaderList(headers);
    Object lock = lockFor(name);
    synchronized (lock) {
      File file = fileFor(name);
      if (file.exists()) {
        throw new ScriptException("CSV \"" + name + "\" already exists.");
      }
      CsvTable table = new CsvTable(normalized, Collections.<List<String>>emptyList());
      writeTable(file, table);
    }
  }

  public void save(String name, List<FlValue> rows) throws ScriptException, IOException {
    validateName(name);
    Object lock = lockFor(name);
    synchronized (lock) {
      CsvTable existing = readTable(name);
      List<String> headers = existing.getHeaders();
      if (headers.isEmpty()) {
        throw new ScriptException("CSV \"" + name + "\" does not exist. Create it first.");
      }
      enforceRowLimit(rows.size());
      CsvTable table = CsvTable.fromFlValueRows(headers, rows);
      writeTable(fileFor(name), table);
    }
  }

  public void appendRow(String name, FlValue row) throws ScriptException, IOException {
    validateName(name);
    if (row == null || row.getKind() != FlValue.Kind.OBJECT) {
      throw new ScriptException("CSV row must be an object (json {...}).");
    }
    Object lock = lockFor(name);
    synchronized (lock) {
      CsvTable table = readTable(name);
      if (table.getHeaders().isEmpty()) {
        throw new ScriptException("CSV \"" + name + "\" does not exist. Create it first.");
      }
      List<List<String>> rows = new ArrayList<List<String>>(table.getRawRows());
      rows.add(CsvTable.fromFlValueRows(table.getHeaders(), Collections.singletonList(row)).getRawRows().get(0));
      enforceRowLimit(rows.size());
      writeTable(fileFor(name), new CsvTable(table.getHeaders(), rows));
    }
  }

  public void delete(String name) throws ScriptException, IOException {
    validateName(name);
    Object lock = lockFor(name);
    synchronized (lock) {
      File file = fileFor(name);
      if (!file.exists()) {
        throw new ScriptException("CSV \"" + name + "\" does not exist.");
      }
      if (!file.delete()) {
        throw new ScriptException("Could not delete CSV \"" + name + "\".");
      }
    }
  }

  public List<FlValue> rows(String name) throws ScriptException, IOException {
    return readTable(name).toFlValueRows();
  }

  public FlValue headers(String name) throws ScriptException, IOException {
    return readTable(name).toFlValueHeaders();
  }

  public long rowCount(String name) throws ScriptException, IOException {
    return readTable(name).rowCount();
  }

  public String text(String name) throws ScriptException, IOException {
    validateName(name);
    Object lock = lockFor(name);
    synchronized (lock) {
      File file = fileFor(name);
      if (!file.exists()) {
        throw new ScriptException("CSV \"" + name + "\" does not exist.");
      }
      byte[] bytes = Files.readAllBytes(file.toPath());
      enforceSizeLimit(bytes.length);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  public boolean exists(String name) throws ScriptException {
    validateName(name);
    return fileFor(name).exists();
  }

  public List<FlValue> filterRows(
      String name, String column, String operator, FlValue expected) throws ScriptException, IOException {
    CsvTable table = readTable(name);
    String col = column == null ? "" : column.trim().toLowerCase(Locale.ROOT);
    if (col.isEmpty()) {
      throw new ScriptException("CSV filter column cannot be empty.");
    }
    String op = operator == null ? "is" : operator.trim().toLowerCase(Locale.ROOT);
    List<FlValue> out = new ArrayList<FlValue>();
    for (Map<String, String> row : table.asRowMaps()) {
      String cell = row.containsKey(col) ? row.get(col) : "";
      if (matchesFilter(cell, op, expected)) {
        Map<String, FlValue> obj = new LinkedHashMap<String, FlValue>();
        for (Map.Entry<String, String> e : row.entrySet()) {
          obj.put(e.getKey(), FlValue.ofString(e.getValue()));
        }
        out.add(FlValue.ofObject(obj));
      }
    }
    return out;
  }

  private boolean matchesFilter(String cell, String op, FlValue expected) {
    if ("contains".equals(op)) {
      return cell.toLowerCase(Locale.ROOT).contains(expected.asString().toLowerCase(Locale.ROOT));
    }
    if (">".equals(op) || "greater than".equals(op)) {
      return Double.compare(parseNumber(cell), expected.asNumber()) > 0;
    }
    if ("<".equals(op) || "less than".equals(op)) {
      return Double.compare(parseNumber(cell), expected.asNumber()) < 0;
    }
    if (">=".equals(op) || "greater than or equal to".equals(op)) {
      return Double.compare(parseNumber(cell), expected.asNumber()) >= 0;
    }
    if ("<=".equals(op) || "less than or equal to".equals(op)) {
      return Double.compare(parseNumber(cell), expected.asNumber()) <= 0;
    }
    if ("!=".equals(op) || "is not".equals(op) || "isn't".equals(op)) {
      return !cell.equalsIgnoreCase(expected.asString());
    }
    return cell.equalsIgnoreCase(expected.asString());
  }

  private static double parseNumber(String s) {
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private CsvTable readTable(String name) throws ScriptException, IOException {
    validateName(name);
    Object lock = lockFor(name);
    synchronized (lock) {
      File file = fileFor(name);
      if (!file.exists()) {
        throw new ScriptException("CSV \"" + name + "\" does not exist.");
      }
      byte[] bytes = Files.readAllBytes(file.toPath());
      enforceSizeLimit(bytes.length);
      String text = new String(bytes, StandardCharsets.UTF_8);
      return CsvParser.parse(text, true);
    }
  }

  private void writeTable(File file, CsvTable table) throws ScriptException, IOException {
    String text = CsvParser.serialize(table);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    enforceSizeLimit(bytes.length);
    enforceRowLimit(table.rowCount());
    Files.write(file.toPath(), bytes);
  }

  private File fileFor(String name) throws ScriptException {
    validateName(name);
    return new File(folder, name + ".csv");
  }

  private Object lockFor(String name) {
    Object lock = new Object();
    Object existing = locks.putIfAbsent(name.toLowerCase(Locale.ROOT), lock);
    return existing != null ? existing : lock;
  }

  public static void validateName(String name) throws ScriptException {
    if (name == null || !NAME_PATTERN.matcher(name.trim()).matches()) {
      throw new ScriptException(
          "Invalid CSV name. Use 1-64 characters: letters, digits, underscore, hyphen.");
    }
  }

  private static List<String> normalizeHeaderList(List<String> headers) throws ScriptException {
    List<String> out = new ArrayList<String>();
    for (int i = 0; i < headers.size(); i++) {
      String h = headers.get(i) == null ? "" : headers.get(i).trim();
      if (h.isEmpty()) {
        h = "col" + (i + 1);
      }
      out.add(h);
    }
    return out;
  }

  public static List<String> parseHeaderString(String headerLine) throws ScriptException {
    if (headerLine == null || headerLine.trim().isEmpty()) {
      throw new ScriptException("CSV headers cannot be empty.");
    }
    List<String> parts = new ArrayList<String>();
    for (String part : headerLine.split(",")) {
      parts.add(part.trim());
    }
    return normalizeHeaderList(parts);
  }

  private static void enforceRowLimit(int rows) throws ScriptException {
    if (rows > MAX_ROWS) {
      throw new ScriptException("CSV row limit exceeded (max " + MAX_ROWS + ").");
    }
  }

  private static void enforceSizeLimit(int bytes) throws ScriptException {
    if (bytes > MAX_BYTES) {
      throw new ScriptException("CSV file size limit exceeded (max " + MAX_BYTES + " bytes).");
    }
  }
}
