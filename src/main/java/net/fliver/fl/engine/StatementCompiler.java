package net.fliver.fl.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fliver.fl.Endpoint;
import net.fliver.fl.ScriptFunction;
import net.fliver.fl.cond.Conditions;
import net.fliver.fl.effect.Effects;
import net.fliver.fl.expr.Expressions;
import net.fliver.fl.lang.Condition;
import net.fliver.fl.lang.Effect;
import net.fliver.fl.lang.Expression;
import net.fliver.fl.lang.ScriptException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Compiles indented .fl body lines into an executable statement tree
 * (effects, if/else if/else, while, loop, function call).
 */
public final class StatementCompiler {
  private static final Pattern LOOP_PLAYERS =
      Pattern.compile("(?i)^loop\\s+(?:all\\s+)?(?:online\\s+)?players\\s*:$");
  private static final Pattern LOOP_LIST =
      Pattern.compile("(?i)^loop\\s+\\{([^}]+)\\}\\s*:$");
  private static final Pattern LOOP_TIMES =
      Pattern.compile("(?i)^loop\\s+(.+?)\\s+times\\s*:$");
  private static final Pattern WHILE =
      Pattern.compile("(?i)^while\\s+(.+?):$");
  private static final Pattern CALL =
      Pattern.compile("(?i)^(?:call|run)\\s+(?:function\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\((.*)\\)\\s*$");
  private static final Pattern RETURN =
      Pattern.compile("(?i)^return(?:\\s+(.+))?$");

  private StatementCompiler() {}

  /**
   * Compiled-statement caches keyed by identity (Endpoint/ScriptFunction
   * don't override equals/hashCode, so default identity semantics apply).
   * ScriptManager.reloadAll()/reloadOne() always build fresh Endpoint and
   * ScriptFunction instances on (re)load, so these caches self-invalidate
   * across reloads - stale entries just become unreachable and are
   * eventually GC'd, no explicit invalidation needed. This avoids
   * recompiling a script body from source on every single HTTP request /
   * every function call.
   */
  private static final Map<Endpoint, List<Statement>> ENDPOINT_CACHE =
      new ConcurrentHashMap<Endpoint, List<Statement>>();
  private static final Map<ScriptFunction, List<Statement>> FUNCTION_CACHE =
      new ConcurrentHashMap<ScriptFunction, List<Statement>>();

  public static List<Statement> compileCached(Endpoint endpoint) throws ScriptException {
    List<Statement> cached = ENDPOINT_CACHE.get(endpoint);
    if (cached != null) {
      return cached;
    }
    List<Statement> compiled = compile(endpoint.getBody());
    ENDPOINT_CACHE.put(endpoint, compiled);
    return compiled;
  }

  private static List<Statement> compileCachedFunction(ScriptFunction fn) throws ScriptException {
    List<Statement> cached = FUNCTION_CACHE.get(fn);
    if (cached != null) {
      return cached;
    }
    List<Statement> compiled = compile(fn.getBody());
    FUNCTION_CACHE.put(fn, compiled);
    return compiled;
  }

  public static List<Statement> compile(List<String> bodyLines) throws ScriptException {
    List<RawLine> raw = new ArrayList<RawLine>();
    for (int i = 0; i < bodyLines.size(); i++) {
      String line = bodyLines.get(i);
      if (line == null) continue;
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      raw.add(new RawLine(i + 1, indentOf(line), trimmed));
    }
    if (raw.isEmpty()) return new ArrayList<Statement>();
    int base = raw.get(0).indent;
    return compileBlock(raw, 0, base).statements;
  }

  private static CompileResult compileBlock(List<RawLine> lines, int start, int baseIndent)
      throws ScriptException {
    List<Statement> out = new ArrayList<Statement>();
    int i = start;
    while (i < lines.size()) {
      RawLine line = lines.get(i);
      if (line.indent < baseIndent) {
        return new CompileResult(out, i);
      }
      if (line.indent > baseIndent) {
        throw new ScriptException("Unexpected indent at script line " + line.number);
      }

      String t = line.text;
      String lower = t.toLowerCase(Locale.ROOT);

      if (lower.startsWith("if ")) {
        List<Condition> conditions = new ArrayList<Condition>();
        List<List<Statement>> bodies = new ArrayList<List<Statement>>();
        conditions.add(Conditions.parse(Conditions.stripIfHeader(t)));
        int childBase = childIndent(lines, i + 1, baseIndent);
        CompileResult thenBlock = compileBlock(lines, i + 1, childBase);
        bodies.add(thenBlock.statements);
        i = thenBlock.nextIndex;

        while (i < lines.size()) {
          RawLine next = lines.get(i);
          if (next.indent != baseIndent) break;
          if (Conditions.isElseIfHeader(next.text)) {
            conditions.add(Conditions.parse(Conditions.stripElseIfHeader(next.text)));
            int elseIfChild = childIndent(lines, i + 1, baseIndent);
            CompileResult elseIfBlock = compileBlock(lines, i + 1, elseIfChild);
            bodies.add(elseIfBlock.statements);
            i = elseIfBlock.nextIndex;
            continue;
          }
          break;
        }

        List<Statement> elseBody = null;
        if (i < lines.size()) {
          RawLine next = lines.get(i);
          if (next.indent == baseIndent
              && next.text.toLowerCase(Locale.ROOT).matches("else\\s*:?")) {
            int elseChild = childIndent(lines, i + 1, baseIndent);
            CompileResult elseBlock = compileBlock(lines, i + 1, elseChild);
            elseBody = elseBlock.statements;
            i = elseBlock.nextIndex;
          }
        }
        out.add(new IfChainStatement(conditions, bodies, elseBody));
        continue;
      }

      Matcher whileM = WHILE.matcher(t);
      if (whileM.matches()) {
        Condition cond = Conditions.parse(whileM.group(1).trim());
        int childBase = childIndent(lines, i + 1, baseIndent);
        CompileResult body = compileBlock(lines, i + 1, childBase);
        out.add(new WhileStatement(cond, body.statements));
        i = body.nextIndex;
        continue;
      }

      if (LOOP_PLAYERS.matcher(t).matches()) {
        int childBase = childIndent(lines, i + 1, baseIndent);
        CompileResult body = compileBlock(lines, i + 1, childBase);
        out.add(new LoopPlayersStatement(body.statements));
        i = body.nextIndex;
        continue;
      }

      Matcher loopList = LOOP_LIST.matcher(t);
      if (loopList.matches()) {
        final String var = loopList.group(1);
        int childBase = childIndent(lines, i + 1, baseIndent);
        CompileResult body = compileBlock(lines, i + 1, childBase);
        out.add(new LoopListStatement(var, body.statements));
        i = body.nextIndex;
        continue;
      }

      Matcher loopTimes = LOOP_TIMES.matcher(t);
      if (loopTimes.matches()) {
        final Expression countExpr = Expressions.parse(loopTimes.group(1).trim());
        int childBase = childIndent(lines, i + 1, baseIndent);
        CompileResult body = compileBlock(lines, i + 1, childBase);
        out.add(new LoopTimesStatement(countExpr, body.statements));
        i = body.nextIndex;
        continue;
      }

      if (lower.equals("else")
          || lower.equals("else:")
          || lower.startsWith("else if ")) {
        return new CompileResult(out, i);
      }

      if (t.endsWith(":")) {
        throw new ScriptException("Unknown block at script line " + line.number + ": " + t);
      }

      if (lower.equals("break") || lower.equals("exit loop") || lower.equals("stop loop")) {
        out.add(new LoopControlStatement(ScriptContext.LoopControl.BREAK));
        i++;
        continue;
      }
      if (lower.equals("continue") || lower.equals("skip") || lower.equals("next loop")) {
        out.add(new LoopControlStatement(ScriptContext.LoopControl.CONTINUE));
        i++;
        continue;
      }

      Matcher ret = RETURN.matcher(t);
      if (ret.matches()) {
        Expression value =
            ret.group(1) == null || ret.group(1).trim().isEmpty()
                ? null
                : Expressions.parse(ret.group(1).trim());
        out.add(new ReturnStatement(value));
        i++;
        continue;
      }

      Matcher call = CALL.matcher(t);
      if (call.matches()) {
        out.add(new CallFunctionStatement(call.group(1), parseArgList(call.group(2))));
        i++;
        continue;
      }

      out.add(new EffectStatement(Effects.parse(t)));
      i++;
    }
    return new CompileResult(out, i);
  }

  private static List<Expression> parseArgList(String raw) throws ScriptException {
    List<Expression> out = new ArrayList<Expression>();
    if (raw == null || raw.trim().isEmpty()) return out;
    List<String> parts = splitArgs(raw);
    for (String p : parts) {
      out.add(Expressions.parse(p.trim()));
    }
    return out;
  }

  private static List<String> splitArgs(String input) {
    List<String> out = new ArrayList<String>();
    StringBuilder cur = new StringBuilder();
    int depth = 0;
    boolean inStr = false;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c == '"' && (i == 0 || input.charAt(i - 1) != '\\')) inStr = !inStr;
      if (!inStr) {
        if (c == '{' || c == '[' || c == '(') depth++;
        if (c == '}' || c == ']' || c == ')') depth--;
        if (c == ',' && depth == 0) {
          out.add(cur.toString().trim());
          cur.setLength(0);
          continue;
        }
      }
      cur.append(c);
    }
    if (cur.length() > 0) out.add(cur.toString().trim());
    return out;
  }

  private static int childIndent(List<RawLine> lines, int from, int baseIndent) {
    if (from >= lines.size() || lines.get(from).indent <= baseIndent) {
      return baseIndent + 4;
    }
    return lines.get(from).indent;
  }

  private static int indentOf(String line) {
    int n = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == ' ') n++;
      else if (c == '\t') n += 4;
      else break;
    }
    return n;
  }

  private static void runBody(List<Statement> body, ScriptContext ctx) throws ScriptException {
    for (Statement s : body) {
      s.run(ctx);
      if (ctx.shouldStop()) return;
      if (ctx.getLoopControl() != ScriptContext.LoopControl.NONE) return;
    }
  }

  public static FlValue invokeFunction(ScriptContext ctx, String name, List<FlValue> args)
      throws ScriptException {
    ScriptFunction fn = ctx.getFunction(name);
    if (fn == null) {
      throw new ScriptException("Unknown function: " + name);
    }
    if (ctx.getFunctionDepth() > 32) {
      throw new ScriptException("Function recursion limit exceeded.");
    }
    Map<String, FlValue> saved = ctx.snapshotLocals();
    FlValue prevReturn = ctx.getReturnValue();
    boolean prevStop = ctx.shouldStop();
    ctx.enterFunction();
    FlValue result = FlValue.ofNull();
    try {
      List<String> params = fn.getParameters();
      for (int i = 0; i < params.size(); i++) {
        FlValue arg = i < args.size() ? args.get(i) : FlValue.ofNull();
        ctx.setVariable(params.get(i), arg);
      }
      ctx.setReturnValue(FlValue.ofNull());
      ctx.clearStop();
      List<Statement> body = compileCachedFunction(fn);
      for (Statement s : body) {
        s.run(ctx);
        if (ctx.shouldStop()) break;
      }
      result = ctx.getReturnValue();
    } finally {
      ctx.leaveFunction();
      ctx.restoreLocals(saved);
      ctx.setReturnValue(prevReturn);
      if (prevStop) ctx.requestStop();
      else ctx.clearStop();
      ctx.setVariable("_returned", result);
    }
    return result;
  }

  private static final class RawLine {
    final int number;
    final int indent;
    final String text;

    RawLine(int number, int indent, String text) {
      this.number = number;
      this.indent = indent;
      this.text = text;
    }
  }

  private static final class CompileResult {
    final List<Statement> statements;
    final int nextIndex;

    CompileResult(List<Statement> statements, int nextIndex) {
      this.statements = statements;
      this.nextIndex = nextIndex;
    }
  }

  public interface Statement {
    void run(ScriptContext ctx) throws ScriptException;
  }

  private static final class EffectStatement implements Statement {
    private final Effect effect;

    EffectStatement(Effect effect) {
      this.effect = effect;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      ctx.consumeStep();
      if (ctx.shouldStop()) return;
      effect.execute(ctx);
    }
  }

  private static final class LoopControlStatement implements Statement {
    private final ScriptContext.LoopControl control;

    LoopControlStatement(ScriptContext.LoopControl control) {
      this.control = control;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      ctx.consumeStep();
      ctx.setLoopControl(control);
    }
  }

  private static final class ReturnStatement implements Statement {
    private final Expression value;

    ReturnStatement(Expression value) {
      this.value = value;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      ctx.consumeStep();
      if (value != null) {
        ctx.setReturnValue(value.evaluate(ctx));
      } else {
        ctx.setReturnValue(FlValue.ofNull());
      }
      ctx.requestStop();
    }
  }

  private static final class CallFunctionStatement implements Statement {
    private final String name;
    private final List<Expression> args;

    CallFunctionStatement(String name, List<Expression> args) {
      this.name = name;
      this.args = args;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      ctx.consumeStep();
      List<FlValue> values = new ArrayList<FlValue>();
      for (Expression e : args) values.add(e.evaluate(ctx));
      invokeFunction(ctx, name, values);
    }
  }

  private static final class IfChainStatement implements Statement {
    private final List<Condition> conditions;
    private final List<List<Statement>> bodies;
    private final List<Statement> elseBody;

    IfChainStatement(
        List<Condition> conditions, List<List<Statement>> bodies, List<Statement> elseBody) {
      this.conditions = conditions;
      this.bodies = bodies;
      this.elseBody = elseBody;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      ctx.consumeStep();
      if (ctx.shouldStop()) return;
      for (int i = 0; i < conditions.size(); i++) {
        if (conditions.get(i).check(ctx)) {
          runBody(bodies.get(i), ctx);
          return;
        }
      }
      if (elseBody != null) runBody(elseBody, ctx);
    }
  }

  private static final class WhileStatement implements Statement {
    private final Condition condition;
    private final List<Statement> body;

    WhileStatement(Condition condition, List<Statement> body) {
      this.condition = condition;
      this.body = body;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      int guard = 0;
      while (condition.check(ctx)) {
        ctx.consumeStep();
        if (ctx.shouldStop()) return;
        if (++guard > 10000) {
          throw new ScriptException("while loop exceeded 10000 iterations");
        }
        ctx.clearLoopControl();
        runBody(body, ctx);
        if (ctx.getLoopControl() == ScriptContext.LoopControl.BREAK) {
          ctx.clearLoopControl();
          return;
        }
        if (ctx.getLoopControl() == ScriptContext.LoopControl.CONTINUE) {
          ctx.clearLoopControl();
          continue;
        }
        if (ctx.shouldStop()) return;
      }
    }
  }

  private static final class LoopPlayersStatement implements Statement {
    private final List<Statement> body;

    LoopPlayersStatement(List<Statement> body) {
      this.body = body;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      for (Player player : Bukkit.getOnlinePlayers()) {
        ctx.consumeStep();
        if (ctx.shouldStop()) return;
        ctx.clearLoopControl();
        ctx.setVariable("loop-player", FlValue.ofString(player.getName()));
        ctx.setVariable("_loop-player", FlValue.ofString(player.getName()));
        runBody(body, ctx);
        if (ctx.getLoopControl() == ScriptContext.LoopControl.BREAK) {
          ctx.clearLoopControl();
          return;
        }
        ctx.clearLoopControl();
      }
    }
  }

  private static final class LoopListStatement implements Statement {
    private final String listVar;
    private final List<Statement> body;

    LoopListStatement(String listVar, List<Statement> body) {
      this.listVar = listVar;
      this.body = body;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      FlValue list = ctx.getVariable(listVar);
      int index = 1;
      for (FlValue item : list.asList()) {
        ctx.consumeStep();
        if (ctx.shouldStop()) return;
        ctx.clearLoopControl();
        ctx.setVariable("loop-value", item);
        ctx.setVariable("_loop-value", item);
        ctx.setVariable("loop-index", FlValue.ofLong(index++));
        runBody(body, ctx);
        if (ctx.getLoopControl() == ScriptContext.LoopControl.BREAK) {
          ctx.clearLoopControl();
          return;
        }
        ctx.clearLoopControl();
      }
    }
  }

  private static final class LoopTimesStatement implements Statement {
    private final Expression countExpr;
    private final List<Statement> body;

    LoopTimesStatement(Expression countExpr, List<Statement> body) {
      this.countExpr = countExpr;
      this.body = body;
    }

    @Override
    public void run(ScriptContext ctx) throws ScriptException {
      int times =
          (int) Math.max(0, Math.min(10000, countExpr.evaluate(ctx).asLong()));
      for (int i = 1; i <= times; i++) {
        ctx.consumeStep();
        if (ctx.shouldStop()) return;
        ctx.clearLoopControl();
        ctx.setVariable("loop-index", FlValue.ofLong(i));
        ctx.setVariable("_loop-index", FlValue.ofLong(i));
        runBody(body, ctx);
        if (ctx.getLoopControl() == ScriptContext.LoopControl.BREAK) {
          ctx.clearLoopControl();
          return;
        }
        ctx.clearLoopControl();
      }
    }
  }
}
