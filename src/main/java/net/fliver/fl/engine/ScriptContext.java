package net.fliver.fl.engine;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.fliver.fl.Endpoint;
import net.fliver.fl.ScriptFunction;
import net.fliver.fl.lang.ScriptException;

/**
 * Per-request execution context (Skript-style locals + response + HTTP meta).
 */
public final class ScriptContext {
  public enum LoopControl {
    NONE,
    BREAK,
    CONTINUE
  }

  private final Map<String, FlValue> locals = new HashMap<String, FlValue>();
  private final Endpoint endpoint;
  private final ScriptMetadata metadata;
  private final String method;
  private final String query;
  private final String requestBody;
  private final Map<String, String> headers;
  private final Map<String, String> queryArgs;
  private final Map<String, String> options;
  private final Map<String, ScriptFunction> functions;
  private final Map<String, String> pathParams = new LinkedHashMap<String, String>();
  private FlValue response;
  private FlValue returnValue;
  private int status = 200;
  private String error;
  private boolean stop;
  private LoopControl loopControl = LoopControl.NONE;
  private int functionDepth;
  /**
   * Total statements executed so far in this request, across every nested
   * loop and function call. Bounds the whole request's work regardless of
   * how loops/recursion combine (local per-loop iteration caps don't compose
   * - e.g. a 10000-iteration loop calling a function that itself loops
   * 10000 times can otherwise multiply to 100M+ statement executions on the
   * main server thread).
   */
  private static final int MAX_STEPS = 200_000;
  private int steps;

  public ScriptContext(
      Endpoint endpoint,
      ScriptMetadata metadata,
      String method,
      String query,
      String requestBody) {
    this(
        endpoint,
        metadata,
        method,
        query,
        requestBody,
        Collections.<String, String>emptyMap(),
        Collections.<String, String>emptyMap(),
        Collections.<String, ScriptFunction>emptyMap());
  }

  public ScriptContext(
      Endpoint endpoint,
      ScriptMetadata metadata,
      String method,
      String query,
      String requestBody,
      Map<String, String> headers,
      Map<String, String> options,
      Map<String, ScriptFunction> functions) {
    this.endpoint = endpoint;
    this.metadata = metadata == null ? ScriptMetadata.empty() : metadata;
    this.method = method == null ? "GET" : method;
    this.query = query == null ? "" : query;
    this.requestBody = requestBody == null ? "" : requestBody;
    this.headers = new LinkedHashMap<String, String>();
    if (headers != null) {
      for (Map.Entry<String, String> e : headers.entrySet()) {
        this.headers.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
      }
    }
    this.queryArgs = parseQuery(this.query);
    this.options =
        options == null
            ? Collections.<String, String>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, String>(options));
    this.functions =
        functions == null
            ? Collections.<String, ScriptFunction>emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<String, ScriptFunction>(functions));
  }

  public Endpoint getEndpoint() {
    return endpoint;
  }

  public ScriptMetadata getMetadata() {
    return metadata;
  }

  /** Returns a metadata value or empty string when unset. */
  public String getMetadataValue(String key) {
    return metadata.get(key);
  }

  public String getMethod() {
    return method;
  }

  public String getQuery() {
    return query;
  }

  public String getRequestBody() {
    return requestBody;
  }

  public String getQueryArg(String name) {
    if (name == null) return "";
    String v = queryArgs.get(name.toLowerCase(Locale.ROOT));
    return v == null ? "" : v;
  }

  public Map<String, String> getQueryArgs() {
    return Collections.unmodifiableMap(queryArgs);
  }

  public String getHeader(String name) {
    if (name == null) return "";
    String v = headers.get(name.toLowerCase(Locale.ROOT));
    return v == null ? "" : v;
  }

  public String getOption(String name) {
    if (name == null) return "";
    String v = options.get(name.toLowerCase(Locale.ROOT));
    return v == null ? "" : v;
  }

  public Map<String, String> getOptions() {
    return options;
  }

  public ScriptFunction getFunction(String name) {
    if (name == null) return null;
    return functions.get(name.toLowerCase(Locale.ROOT));
  }

  public Map<String, ScriptFunction> getFunctions() {
    return functions;
  }

  public void setVariable(String name, FlValue value) {
    locals.put(normalize(name), value == null ? FlValue.ofNull() : value);
  }

  public FlValue getVariable(String name) {
    FlValue v = locals.get(normalize(name));
    return v == null ? FlValue.ofNull() : v;
  }

  public boolean hasVariable(String name) {
    return locals.containsKey(normalize(name));
  }

  public void deleteVariable(String name) {
    locals.remove(normalize(name));
  }

  public Map<String, FlValue> snapshotLocals() {
    return new HashMap<String, FlValue>(locals);
  }

  public void restoreLocals(Map<String, FlValue> snapshot) {
    locals.clear();
    locals.putAll(snapshot);
  }

  public void setPathParams(Map<String, String> pathParams) {
    this.pathParams.clear();
    if (pathParams == null) return;
    for (Map.Entry<String, String> e : pathParams.entrySet()) {
      if (e.getKey() == null) continue;
      String key = e.getKey().toLowerCase(Locale.ROOT);
      String val = e.getValue() == null ? "" : e.getValue();
      this.pathParams.put(key, val);
      // Skript-friendly locals: {player} and {_player}
      setVariable(key, FlValue.ofString(val));
      if (!key.startsWith("_")) {
        setVariable("_" + key, FlValue.ofString(val));
      }
    }
  }

  public String getPathArg(String name) {
    if (name == null) return "";
    String v = pathParams.get(name.toLowerCase(Locale.ROOT));
    return v == null ? "" : v;
  }

  public Map<String, String> getPathArgs() {
    return Collections.unmodifiableMap(pathParams);
  }

  public void setResponse(FlValue value) {
    this.response = value;
  }

  public FlValue getResponse() {
    return response;
  }

  public void setReturnValue(FlValue value) {
    this.returnValue = value;
  }

  public FlValue getReturnValue() {
    return returnValue == null ? FlValue.ofNull() : returnValue;
  }

  public void setStatus(int status) {
    if (status >= 100 && status <= 599) this.status = status;
  }

  public int getStatus() {
    return status;
  }

  public void setError(String error) {
    this.error = error;
  }

  public String getError() {
    return error;
  }

  public void requestStop() {
    this.stop = true;
  }

  public boolean shouldStop() {
    return stop;
  }

  public void clearStop() {
    this.stop = false;
  }

  public void setLoopControl(LoopControl control) {
    this.loopControl = control == null ? LoopControl.NONE : control;
  }

  public LoopControl getLoopControl() {
    return loopControl;
  }

  public void clearLoopControl() {
    this.loopControl = LoopControl.NONE;
  }

  public int enterFunction() {
    return ++functionDepth;
  }

  public void leaveFunction() {
    if (functionDepth > 0) functionDepth--;
  }

  public int getFunctionDepth() {
    return functionDepth;
  }

  /** Called once per executed statement; throws once the request's step budget is exhausted. */
  public void consumeStep() throws ScriptException {
    if (++steps > MAX_STEPS) {
      throw new ScriptException("Script exceeded maximum execution steps (possible infinite loop).");
    }
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> out = new LinkedHashMap<String, String>();
    if (query == null || query.isEmpty()) return out;
    String q = query;
    if (q.startsWith("?")) q = q.substring(1);
    String[] parts = q.split("&");
    for (String part : parts) {
      if (part.isEmpty()) continue;
      int eq = part.indexOf('=');
      String key;
      String val;
      if (eq < 0) {
        key = decode(part);
        val = "";
      } else {
        key = decode(part.substring(0, eq));
        val = decode(part.substring(eq + 1));
      }
      out.put(key.toLowerCase(Locale.ROOT), val);
    }
    return out;
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s.replace('+', ' '), "UTF-8");
    } catch (UnsupportedEncodingException e) {
      return s;
    }
  }

  private static String normalize(String name) {
    String n = name.trim();
    if (n.startsWith("{") && n.endsWith("}") && n.length() >= 2) {
      n = n.substring(1, n.length() - 1);
    }
    return n.toLowerCase(Locale.ROOT);
  }
}
