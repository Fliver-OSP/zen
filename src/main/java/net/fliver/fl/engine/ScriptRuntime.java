package net.fliver.fl.engine;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.fliver.fl.Endpoint;
import net.fliver.fl.ScriptFunction;
import net.fliver.fl.lang.ScriptException;

/** Executes an endpoint's .fl body for one inbound HTTP request. */
public final class ScriptRuntime {
  private ScriptRuntime() {}

  public static ScriptContext execute(
      Endpoint endpoint,
      ScriptMetadata metadata,
      String method,
      String query,
      String requestBody)
      throws ScriptException {
    return execute(
        endpoint,
        metadata,
        method,
        query,
        requestBody,
        Collections.<String, String>emptyMap(),
        Collections.<String, String>emptyMap(),
        Collections.<String, ScriptFunction>emptyMap(),
        Collections.<String, String>emptyMap());
  }

  public static ScriptContext execute(
      Endpoint endpoint,
      ScriptMetadata metadata,
      String method,
      String query,
      String requestBody,
      Map<String, String> headers,
      Map<String, String> options,
      Map<String, ScriptFunction> functions)
      throws ScriptException {
    return execute(
        endpoint,
        metadata,
        method,
        query,
        requestBody,
        headers,
        options,
        functions,
        Collections.<String, String>emptyMap());
  }

  public static ScriptContext execute(
      Endpoint endpoint,
      ScriptMetadata metadata,
      String method,
      String query,
      String requestBody,
      Map<String, String> headers,
      Map<String, String> options,
      Map<String, ScriptFunction> functions,
      Map<String, String> pathParams)
      throws ScriptException {
    ScriptContext ctx =
        new ScriptContext(
            endpoint, metadata, method, query, requestBody, headers, options, functions);
    ctx.setPathParams(pathParams);
    List<StatementCompiler.Statement> statements = StatementCompiler.compileCached(endpoint);
    for (StatementCompiler.Statement statement : statements) {
      statement.run(ctx);
      if (ctx.getError() != null || ctx.shouldStop()) break;
    }
    return ctx;
  }

  public static String responseJson(ScriptContext ctx) {
    FlValue response = ctx.getResponse();
    if (response == null || response.isNull()) {
      return "{\"ok\":true}";
    }
    return response.toJson();
  }
}
