package net.fliver.fl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.fliver.fl.engine.ScriptContext;
import net.fliver.fl.engine.ScriptMetadata;
import net.fliver.fl.engine.StatementCompiler;
import net.fliver.fl.lang.ScriptException;
import org.junit.jupiter.api.Test;

class ScriptErrorsTest {
  private static ScriptException compileFails(List<String> body, List<Integer> lines) {
    return compileFails("api/hello.fl", body, lines);
  }

  private static ScriptException compileFails(String file, List<String> body, List<Integer> lines) {
    try {
      StatementCompiler.compile(body, file, lines);
    } catch (ScriptException e) {
      return e;
    }
    fail("expected ScriptException");
    return null;
  }

  private static ScriptContext context(Endpoint endpoint) {
    return new ScriptContext(endpoint, ScriptMetadata.empty(), "GET", "", "");
  }

  @Test
  void unknownEffectReportsFileLineAndSnippet() {
    List<String> body = Arrays.asList("set {x} to 1", "frobnicate the wobble");
    ScriptException e = compileFails(body, Arrays.asList(10, 11));
    assertEquals("unknown-effect", e.getCode());
    assertEquals(Integer.valueOf(11), Integer.valueOf(e.getLine()));
    assertEquals("api/hello.fl", e.getFileName());
    assertEquals("frobnicate the wobble", e.getSnippet());
    assertTrue(e.getMessage().contains("(at api/hello.fl:11)"));
    assertTrue(e.getMessage().contains("[unknown-effect]"));
  }

  @Test
  void unknownBlockReportsFileLine() {
    List<String> body = Arrays.asList("set {x} to 1", "frobnicate:");
    ScriptException e = compileFails(body, Arrays.asList(4, 5));
    assertEquals("unknown-block", e.getCode());
    assertEquals(5, e.getLine());
    assertTrue(e.getMessage().contains("(at api/hello.fl:5)"));
  }

  @Test
  void runtimeErrorGetsStatementLine() throws Exception {
    List<String> body = Arrays.asList("set {x} to 1", "call nope()");
    List<StatementCompiler.Statement> statements =
        StatementCompiler.compile(body, "api/hello.fl", Arrays.asList(20, 21));
    ScriptContext ctx =
        context(new Endpoint("hello", "api/hello.fl", 1, body, null, Arrays.asList(20, 21)));
    try {
      for (StatementCompiler.Statement s : statements) {
        StatementCompiler.runStatement(s, ctx);
      }
    } catch (ScriptException e) {
      assertEquals("unknown-function", e.getCode());
      assertEquals(21, e.getLine());
      assertEquals("call nope()", e.getSnippet());
      assertTrue(e.getMessage().contains("(at api/hello.fl:21)"));
      return;
    }
    fail("expected ScriptException");
  }

  @Test
  void parserTrackedLinesReachCompileErrors() {
    List<String> file =
        Arrays.asList(
            "on fliver request \"hello\":",
            "    set {x} to 1",
            "    frobnicate the wobble");
    FlScript script = new FlParser(null).parse("hello.fl", file);
    assertTrue(script.getErrors().isEmpty());
    Endpoint endpoint = script.getEndpoints().get(0);
    assertEquals(Arrays.asList(2, 3), endpoint.getBodyLineNumbers());
    ScriptException e =
        compileFails(endpoint.getSourceFile(), endpoint.getBody(), endpoint.getBodyLineNumbers());
    assertEquals(3, e.getLine());
    assertEquals("hello.fl", e.getFileName());
  }

  @Test
  void missingLinesFallBackToBodyRelativeNumbers() {
    List<String> body = Arrays.asList("set {x} to 1", "frobnicate:");
    ScriptException e = compileFails(body, null);
    assertEquals(2, e.getLine());
    assertEquals("api/hello.fl", e.getFileName());
  }

  @Test
  void shortLineListFallsBackPerLine() {
    List<String> body = Arrays.asList("set {x} to 1", "frobnicate:");
    ScriptException e = compileFails(body, Collections.singletonList(40));
    assertEquals(2, e.getLine());
  }

  @Test
  void legacyCompileKeepsBodyRelativeNumbers() {
    List<String> body = Arrays.asList("set {x} to 1", "frobnicate:");
    try {
      StatementCompiler.compile(body);
    } catch (ScriptException e) {
      assertEquals(2, e.getLine());
      assertTrue(e.getMessage().contains("(at <unknown>:2)"));
      return;
    }
    fail("expected ScriptException");
  }
}
