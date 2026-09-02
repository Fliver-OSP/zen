package net.fliver.fl.expr;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fliver.fl.engine.FlValue;
import net.fliver.fl.engine.ScriptContext;
import net.fliver.fl.lang.Expression;
import net.fliver.fl.lang.ScriptException;
import net.fliver.fl.util.ServerMetrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Parses Skript-flavoured expression text into an {@link Expression} tree.
 * Deliberately inspired by Skript UX — not a fork of SkriptLang/Skript (GPL).
 */
public final class Expressions {
  private static final Pattern VAR = Pattern.compile("^\\{([^}]+)\\}$");
  private static final Pattern STRING = Pattern.compile("^\"(.*)\"$", Pattern.DOTALL);
  private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
  private static final Pattern SIZE_OF =
      Pattern.compile("(?i)^(?:size|length|amount|number)\\s+of\\s+(.+)$");
  private static final Pattern ROUND_OF =
      Pattern.compile("(?i)^(?:rounded|round(?:ed)?(?:\\s+value\\s+of)?)\\s+(.+)$");
  private static final Pattern FLOOR_OF =
      Pattern.compile("(?i)^(?:floor(?:ed)?(?:\\s+value\\s+of)?)\\s+(.+)$");
  private static final Pattern CEIL_OF =
      Pattern.compile("(?i)^(?:ceil(?:ed|ing)?(?:\\s+value\\s+of)?)\\s+(.+)$");

  private Expressions() {}

  public static Expression parse(String raw) throws ScriptException {
    net.fliver.fl.builtins.BuiltinSyntax.ensureLoaded();
    String text = raw.trim();
    if (text.isEmpty()) throw new ScriptException("Empty expression.");

    // Parentheses
    if (text.startsWith("(") && text.endsWith(")") && balancedParens(text)) {
      return parse(text.substring(1, text.length() - 1).trim());
    }

    // json constructors before comparisons — otherwise `=` / `is` inside
    // strings or `json of "k" = v` is eaten as a boolean comparison.
    if (text.toLowerCase(Locale.ROOT).startsWith("json ")) {
      return parseJsonConstructor(text.substring(5).trim());
    }

    // Bare object literals only when inner content has a top-level ':'.
    // Do NOT treat {_var} as an object. Colon must be checked on the INNER
    // text — looking at the full `{...}` keeps depth>=1 and misses every ':'.
    if (text.startsWith("{") && text.endsWith("}") && balancedBraces(text, '{', '}')) {
      String inner = text.substring(1, text.length() - 1).trim();
      if (indexOfTopLevel(inner, ':') >= 0) {
        return parseJsonObjectLiteral(inner);
      }
    }
    if (text.startsWith("[") && text.endsWith("]") && balancedBraces(text, '[', ']')) {
      return parseJsonArrayLiteral(text.substring(1, text.length() - 1).trim());
    }

    // Skript-ish ternary: if <cond> then <a> else <b>
    TernaryParts tp = parseTernary(text);
    if (tp != null) {
      final Expression cond = parse(tp.cond);
      final Expression whenTrue = parse(tp.thenPart);
      final Expression whenFalse = parse(tp.elsePart);
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) throws ScriptException {
          return cond.evaluate(ctx).asBoolean()
              ? whenTrue.evaluate(ctx)
              : whenFalse.evaluate(ctx);
        }
      };
    }

    Expression registryExpr = net.fliver.fl.registry.SyntaxRegistry.get().tryExpression(text);
    if (registryExpr != null) {
      return registryExpr;
    }

    ComparisonParts cmp = findTopLevelComparison(text);
    if (cmp != null) {
      final Expression left = parse(cmp.left);
      final Expression right = parse(cmp.right);
      final String op = cmp.op;
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) throws ScriptException {
          return FlValue.ofBoolean(compare(left.evaluate(ctx), right.evaluate(ctx), op));
        }
      };
    }

    return parseArithmetic(text);
  }

  private static final class TernaryParts {
    final String cond;
    final String thenPart;
    final String elsePart;

    TernaryParts(String cond, String thenPart, String elsePart) {
      this.cond = cond;
      this.thenPart = thenPart;
      this.elsePart = elsePart;
    }
  }

  private static TernaryParts parseTernary(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    if (!lower.startsWith("if ")) return null;
    int thenAt = indexOfTopLevelKeyword(text, " then ");
    if (thenAt < 0) return null;
    int elseAt = indexOfTopLevelKeyword(text, " else ");
    if (elseAt < 0 || elseAt < thenAt) return null;
    String cond = text.substring(3, thenAt).trim();
    String thenPart = text.substring(thenAt + 6, elseAt).trim();
    String elsePart = text.substring(elseAt + 6).trim();
    if (cond.isEmpty() || thenPart.isEmpty() || elsePart.isEmpty()) return null;
    return new TernaryParts(cond, thenPart, elsePart);
  }

  private static final class ComparisonParts {
    final String left;
    final String op;
    final String right;

    ComparisonParts(String left, String op, String right) {
      this.left = left;
      this.op = op;
      this.right = right;
    }
  }

  /** Finds a comparison operator only outside strings / braces / brackets / parens. */
  private static ComparisonParts findTopLevelComparison(String text) {
    // Never treat `json ...` as a comparison — `=` / `is` belong to the constructor.
    if (text.toLowerCase(Locale.ROOT).startsWith("json ")) return null;

    String[] ops =
        new String[] {" is not ", " isn't ", " is ", " == ", " != ", " >= ", " <= ", " = ", " > ", " < "};
    // Longer ops first (already ordered). Scan left-to-right for first hit at depth 0.
    String lower = text.toLowerCase(Locale.ROOT);
    int depthBrace = 0;
    int depthBracket = 0;
    int depthParen = 0;
    boolean inStr = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inStr = !inStr;
      if (inStr) continue;
      if (c == '{') depthBrace++;
      else if (c == '}') depthBrace = Math.max(0, depthBrace - 1);
      else if (c == '[') depthBracket++;
      else if (c == ']') depthBracket = Math.max(0, depthBracket - 1);
      else if (c == '(') depthParen++;
      else if (c == ')') depthParen = Math.max(0, depthParen - 1);
      if (depthBrace != 0 || depthBracket != 0 || depthParen != 0) continue;
      for (String op : ops) {
        if (lower.startsWith(op, i)) {
          String left = text.substring(0, i).trim();
          String right = text.substring(i + op.length()).trim();
          if (left.isEmpty() || right.isEmpty()) continue;
          return new ComparisonParts(left, op.trim().toLowerCase(Locale.ROOT), right);
        }
      }
    }
    return null;
  }

  private static int indexOfTopLevelKeyword(String text, String keyword) {
    String lower = text.toLowerCase(Locale.ROOT);
    String key = keyword.toLowerCase(Locale.ROOT);
    int depthBrace = 0;
    int depthBracket = 0;
    int depthParen = 0;
    boolean inStr = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inStr = !inStr;
      if (inStr) continue;
      if (c == '{') depthBrace++;
      else if (c == '}') depthBrace = Math.max(0, depthBrace - 1);
      else if (c == '[') depthBracket++;
      else if (c == ']') depthBracket = Math.max(0, depthBracket - 1);
      else if (c == '(') depthParen++;
      else if (c == ')') depthParen = Math.max(0, depthParen - 1);
      if (depthBrace == 0
          && depthBracket == 0
          && depthParen == 0
          && lower.startsWith(key, i)) {
        return i;
      }
    }
    return -1;
  }

  private static boolean balancedBraces(String text, char open, char close) {
    int depth = 0;
    boolean inStr = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inStr = !inStr;
      if (inStr) continue;
      if (c == open) depth++;
      if (c == close) {
        depth--;
        if (depth == 0 && i < text.length() - 1) return false;
        if (depth < 0) return false;
      }
    }
    return depth == 0;
  }

  private static boolean balancedParens(String text) {
    int depth = 0;
    boolean inStr = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) inStr = !inStr;
      if (inStr) continue;
      if (c == '(') depth++;
      if (c == ')') {
        depth--;
        if (depth == 0 && i < text.length() - 1) return false;
      }
    }
    return depth == 0;
  }

  /** Additive / multiplicative ops: {@code {_a} + 1}, {@code player count * 2}. */
  private static Expression parseArithmetic(String text) throws ScriptException {
    List<String> addParts = splitArithmetic(text, "+-");
    if (addParts.size() > 1) {
      Expression acc = parseArithmetic(addParts.get(0));
      for (int i = 1; i < addParts.size(); i += 2) {
        final Expression left = acc;
        final String op = addParts.get(i);
        final Expression right = parseArithmetic(addParts.get(i + 1));
        acc =
            new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                double a = left.evaluate(ctx).asNumber();
                double b = right.evaluate(ctx).asNumber();
                return FlValue.ofNumber(op.equals("+") ? a + b : a - b);
              }
            };
      }
      return acc;
    }

    List<String> mulParts = splitArithmetic(text, "*/%");
    if (mulParts.size() > 1) {
      Expression acc = parsePrimary(mulParts.get(0));
      for (int i = 1; i < mulParts.size(); i += 2) {
        final Expression left = acc;
        final String op = mulParts.get(i);
        final Expression right = parsePrimary(mulParts.get(i + 1));
        acc =
            new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                double a = left.evaluate(ctx).asNumber();
                double b = right.evaluate(ctx).asNumber();
                if (op.equals("/")) {
                  return FlValue.ofNumber(b == 0 ? 0 : a / b);
                }
                if (op.equals("%")) {
                  return FlValue.ofNumber(b == 0 ? 0 : a % b);
                }
                return FlValue.ofNumber(a * b);
              }
            };
      }
      return acc;
    }

    return parsePrimary(text);
  }

  /**
   * Splits {@code a + b - c} into [a, +, b, -, c] at top level only. Leading
   * unary minus stays attached to the first operand ({@code -3 + 1}).
   */
  private static List<String> splitArithmetic(String input, String ops) {
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
        if (depth == 0 && ops.indexOf(c) >= 0) {
          // Skip unary +/- at start of an operand.
          if (cur.toString().trim().isEmpty()) {
            cur.append(c);
            continue;
          }
          out.add(cur.toString().trim());
          out.add(String.valueOf(c));
          cur.setLength(0);
          continue;
        }
      }
      cur.append(c);
    }
    if (cur.length() > 0) out.add(cur.toString().trim());
    if (out.size() == 1) return out;
    // Need odd length: operand (op operand)*
    if (out.size() % 2 == 0) {
      List<String> single = new ArrayList<String>();
      single.add(input.trim());
      return single;
    }
    return out;
  }

  private static Expression parsePrimary(String text) throws ScriptException {
    String t = text.trim();

    // Literals first — never let registry patterns steal them.
    Matcher var = VAR.matcher(t);
    if (var.matches()) {
      final String name = var.group(1);
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return ctx.getVariable(name);
        }
      };
    }

    Matcher str = STRING.matcher(t);
    if (str.matches()) {
      final String lit = unescape(str.group(1));
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return interpolate(lit, ctx);
        }
      };
    }

    if (NUMBER.matcher(t).matches()) {
      final double n = Double.parseDouble(t);
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNumber(n);
        }
      };
    }

    if (t.equalsIgnoreCase("true")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofBoolean(true);
        }
      };
    }
    if (t.equalsIgnoreCase("false")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofBoolean(false);
        }
      };
    }

    String lower = t.toLowerCase(Locale.ROOT);

    if (lower.equals("null") || lower.equals("none")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNull();
        }
      };
    }

    // json {..} / json of .. must run before registry (registry had a
    // conflicting "json of/from" parse pattern).
    if (lower.startsWith("json ")) {
      return parseJsonConstructor(t.substring(5).trim());
    }

    // functionName(args...) before long phrase matchers
    Matcher fnCall = Pattern.compile("^([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\((.*)\\)$").matcher(t);
    if (fnCall.matches()) {
      final String name = fnCall.group(1);
      final List<Expression> args = new ArrayList<Expression>();
      String argRaw = fnCall.group(2).trim();
      if (!argRaw.isEmpty()) {
        for (String part : splitTopLevel(argRaw, ',')) {
          args.add(parse(part.trim()));
        }
      }
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) throws ScriptException {
          List<FlValue> values = new ArrayList<FlValue>();
          for (Expression e : args) values.add(e.evaluate(ctx));
          return net.fliver.fl.engine.StatementCompiler.invokeFunction(
              ctx, name, values);
        }
      };
    }

    Expression registered =
        net.fliver.fl.registry.SyntaxRegistry.get().tryExpression(t);
    if (registered != null) return registered;

    if (lower.equals("player count")
        || lower.equals("number of players")
        || lower.equals("number of online players")
        || lower.equals("online players count")
        || lower.equals("amount of players")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofLong(Bukkit.getOnlinePlayers().size());
        }
      };
    }

    // Server / JVM uptime (Skript-style wording).
    if (lower.equals("uptime")
        || lower.equals("server uptime")
        || lower.equals("uptime in milliseconds")
        || lower.equals("milliseconds the server has been online")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofLong(jvmUptimeMs());
        }
      };
    }
    if (lower.equals("uptime in seconds")
        || lower.equals("seconds online")
        || lower.equals("seconds the server has been online")
        || lower.equals("server uptime in seconds")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNumber(jvmUptimeMs() / 1000.0);
        }
      };
    }
    if (lower.equals("uptime in minutes")
        || lower.equals("minutes online")
        || lower.equals("minutes the server has been online")
        || lower.equals("server uptime in minutes")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNumber(jvmUptimeMs() / 60000.0);
        }
      };
    }
    if (lower.equals("uptime in hours")
        || lower.equals("hours online")
        || lower.equals("hours the server has been online")
        || lower.equals("server uptime in hours")
        || lower.equals("server hours online")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNumber(jvmUptimeMs() / 3600000.0);
        }
      };
    }

    if (lower.equals("max players") || lower.equals("maximum players")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofLong(Bukkit.getMaxPlayers());
        }
      };
    }

    // TPS (ticks per second). Only Paper/Spigot forks expose Bukkit.getTPS();
    // the floor API (1.8.8) doesn't have it, so it's called via reflection and
    // falls back to 20.0 (the nominal rate) when unavailable.
    if (lower.equals("tps") || lower.equals("server tps") || lower.equals("ticks per second")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNumber(currentTps());
        }
      };
    }

    // JVM heap allotted to the server via -Xmx, in GB (what people mean by
    // "how much RAM was the server given").
    if (lower.equals("max memory in gb")
        || lower.equals("allocated memory in gb")
        || lower.equals("allocated ram in gb")
        || lower.equals("max ram in gb")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNumber(Runtime.getRuntime().maxMemory() / 1073741824.0);
        }
      };
    }

    if (lower.equals("used memory in gb") || lower.equals("used ram in gb")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          Runtime rt = Runtime.getRuntime();
          return FlValue.ofNumber((rt.totalMemory() - rt.freeMemory()) / 1073741824.0);
        }
      };
    }

    if (lower.equals("total ram in gb")
        || lower.equals("total memory in gb")
        || lower.equals("system ram in gb")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNumber(ServerMetrics.totalPhysicalMemoryGb());
        }
      };
    }

    if (lower.equals("server disk usage in gb") || lower.equals("disk usage in gb")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofNumber(ServerMetrics.serverDiskUsageGb());
        }
      };
    }

    if (lower.equals("names of all players")
        || lower.equals("all players' names")
        || lower.equals("online players' names")
        || lower.equals("list of online players")
        || lower.equals("online player names")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          List<String> names = new ArrayList<String>();
          for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
          }
          return FlValue.ofStrings(names);
        }
      };
    }

    if (lower.equals("motd") || lower.equals("server motd")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofString(String.valueOf(Bukkit.getMotd()));
        }
      };
    }

    if (lower.equals("server name") || lower.equals("name of the server")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofString(Bukkit.getName());
        }
      };
    }

    if (lower.equals("bukkit version") || lower.equals("minecraft version")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofString(Bukkit.getBukkitVersion());
        }
      };
    }

    if (lower.equals("request method") || lower.equals("the request method")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofString(ctx.getMethod());
        }
      };
    }

    if (lower.equals("request body") || lower.equals("the request body")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofString(ctx.getRequestBody());
        }
      };
    }

    if (lower.equals("request query") || lower.equals("the query string")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofString(ctx.getQuery());
        }
      };
    }

    if (lower.equals("endpoint") || lower.equals("the endpoint") || lower.equals("endpoint name")) {
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) {
          return FlValue.ofString(ctx.getEndpoint().getPath());
        }
      };
    }

    Matcher sizeOf = SIZE_OF.matcher(t);
    if (sizeOf.matches()) {
      final Expression inner = parse(sizeOf.group(1).trim());
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) throws ScriptException {
          FlValue v = inner.evaluate(ctx);
          if (v.getKind() == FlValue.Kind.LIST) return FlValue.ofLong(v.asList().size());
          if (v.getKind() == FlValue.Kind.OBJECT) return FlValue.ofLong(v.asObject().size());
          if (v.getKind() == FlValue.Kind.STRING) return FlValue.ofLong(v.asString().length());
          return FlValue.ofLong(v.isNull() ? 0 : 1);
        }
      };
    }

    Matcher roundOf = ROUND_OF.matcher(t);
    if (roundOf.matches()) {
      final Expression inner = parse(roundOf.group(1).trim());
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) throws ScriptException {
          return FlValue.ofLong(Math.round(inner.evaluate(ctx).asNumber()));
        }
      };
    }

    Matcher floorOf = FLOOR_OF.matcher(t);
    if (floorOf.matches()) {
      final Expression inner = parse(floorOf.group(1).trim());
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) throws ScriptException {
          return FlValue.ofLong((long) Math.floor(inner.evaluate(ctx).asNumber()));
        }
      };
    }

    Matcher ceilOf = CEIL_OF.matcher(t);
    if (ceilOf.matches()) {
      final Expression inner = parse(ceilOf.group(1).trim());
      return new Expression() {
        @Override
        public FlValue evaluate(ScriptContext ctx) throws ScriptException {
          return FlValue.ofLong((long) Math.ceil(inner.evaluate(ctx).asNumber()));
        }
      };
    }

    throw new ScriptException("Unknown expression: " + text);
  }

  private static long jvmUptimeMs() {
    return ManagementFactory.getRuntimeMXBean().getUptime();
  }

  /**
   * Reads Paper/Spigot's 1-minute TPS average via reflection, since
   * {@code Bukkit.getTPS()} isn't part of the 1.8.8 floor API this plugin is
   * compiled against. Returns 20.0 (nominal) on servers that don't expose it.
   */
  private static double currentTps() {
    try {
      Object tps = Bukkit.class.getMethod("getTPS").invoke(null);
      if (tps instanceof double[] && ((double[]) tps).length > 0) {
        return Math.min(20.0, ((double[]) tps)[0]);
      }
    } catch (Throwable ignored) {
      // Not a Paper/Spigot fork exposing getTPS() — fall through to the default.
    }
    return 20.0;
  }

  /**
   * Supports: {@code json {"players": {_players}, "online": {_online}}} and
   * a lighter form {@code json of "players" = {_players} and "online" = {_online}}.
   */
  private static Expression parseJsonConstructor(String body) throws ScriptException {
    String b = body.trim();
    if (b.startsWith("{") && balancedBraces(b, '{', '}')) {
      return parseJsonObjectLiteral(b.substring(1, b.length() - 1).trim());
    }
    if (b.startsWith("[") && balancedBraces(b, '[', ']')) {
      return parseJsonArrayLiteral(b.substring(1, b.length() - 1).trim());
    }
    if (b.toLowerCase(Locale.ROOT).startsWith("of ")) {
      return parseJsonOfForm(b.substring(3).trim());
    }
    throw new ScriptException(
        "Invalid json constructor. Use: json {\"key\": {_var}, ...} or json of \"key\" = {_var} and ...");
  }

  private static Expression parseJsonObjectLiteral(String inner) throws ScriptException {
    final List<String> keys = new ArrayList<String>();
    final List<Expression> values = new ArrayList<Expression>();
    if (!inner.isEmpty()) {
      List<String> parts = splitTopLevel(inner, ',');
      for (String part : parts) {
        int colon = indexOfTopLevel(part, ':');
        if (colon < 0) throw new ScriptException("Invalid json entry: " + part);
        String keyRaw = part.substring(0, colon).trim();
        String valRaw = part.substring(colon + 1).trim();
        keys.add(unquote(keyRaw));
        values.add(parse(valRaw));
      }
    }
    return new Expression() {
      @Override
      public FlValue evaluate(ScriptContext ctx) throws ScriptException {
        java.util.LinkedHashMap<String, FlValue> map =
            new java.util.LinkedHashMap<String, FlValue>();
        for (int i = 0; i < keys.size(); i++) {
          map.put(keys.get(i), values.get(i).evaluate(ctx));
        }
        return FlValue.ofObject(map);
      }
    };
  }

  private static Expression parseJsonArrayLiteral(String inner) throws ScriptException {
    final List<Expression> values = new ArrayList<Expression>();
    if (!inner.isEmpty()) {
      for (String part : splitTopLevel(inner, ',')) {
        values.add(parse(part.trim()));
      }
    }
    return new Expression() {
      @Override
      public FlValue evaluate(ScriptContext ctx) throws ScriptException {
        List<FlValue> list = new ArrayList<FlValue>();
        for (Expression e : values) list.add(e.evaluate(ctx));
        return FlValue.ofList(list);
      }
    };
  }

  private static Expression parseJsonOfForm(String body) throws ScriptException {
    // "players" = {_players} and "online" = {_online}
    // also accepts "players": {_players} (colon form)
    final List<String> keys = new ArrayList<String>();
    final List<Expression> values = new ArrayList<Expression>();
    List<String> parts = splitTopLevel(body, " and ");
    for (String part : parts) {
      int sep = indexOfJsonOfSeparator(part);
      if (sep < 0) throw new ScriptException("Invalid json of entry: " + part);
      keys.add(unquote(part.substring(0, sep).trim()));
      values.add(parse(part.substring(sep + 1).trim()));
    }
    return new Expression() {
      @Override
      public FlValue evaluate(ScriptContext ctx) throws ScriptException {
        java.util.LinkedHashMap<String, FlValue> map =
            new java.util.LinkedHashMap<String, FlValue>();
        for (int i = 0; i < keys.size(); i++) {
          map.put(keys.get(i), values.get(i).evaluate(ctx));
        }
        return FlValue.ofObject(map);
      }
    };
  }

  /** Index of top-level `=` or `:` that separates key from value in `json of` entries. */
  private static int indexOfJsonOfSeparator(String part) {
    int eq = indexOfTopLevel(part, '=');
    int colon = indexOfTopLevel(part, ':');
    if (eq < 0) return colon;
    if (colon < 0) return eq;
    return Math.min(eq, colon);
  }

  private static FlValue interpolate(String template, ScriptContext ctx) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < template.length()) {
      int start = template.indexOf("%{", i);
      if (start < 0) {
        out.append(template.substring(i));
        break;
      }
      out.append(template.substring(i, start));
      int end = template.indexOf("}%", start);
      if (end < 0) {
        out.append(template.substring(start));
        break;
      }
      String var = template.substring(start + 2, end);
      out.append(ctx.getVariable(var).asString());
      i = end + 2;
    }
    return FlValue.ofString(out.toString());
  }

  private static boolean compare(FlValue left, FlValue right, String op) {
    if (op.equals("is") || op.equals("=") || op.equals("==")) return left.equalsValue(right);
    if (op.equals("is not") || op.equals("isn't") || op.equals("!=")) return !left.equalsValue(right);
    double a = left.asNumber();
    double b = right.asNumber();
    if (op.equals(">")) return a > b;
    if (op.equals(">=")) return a >= b;
    if (op.equals("<")) return a < b;
    if (op.equals("<=")) return a <= b;
    return false;
  }

  private static String unescape(String s) {
    return s.replace("\\\"", "\"").replace("\\\\", "\\");
  }

  private static String unquote(String s) {
    String t = s.trim();
    if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
      return unescape(t.substring(1, t.length() - 1));
    }
    return t;
  }

  private static List<String> splitTopLevel(String input, char sep) {
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
        if (c == sep && depth == 0) {
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

  private static List<String> splitTopLevel(String input, String sep) {
    List<String> out = new ArrayList<String>();
    String lower = input.toLowerCase(Locale.ROOT);
    String sepL = sep.toLowerCase(Locale.ROOT);
    int start = 0;
    int depth = 0;
    boolean inStr = false;
    for (int i = 0; i < input.length(); ) {
      char c = input.charAt(i);
      if (c == '"' && (i == 0 || input.charAt(i - 1) != '\\')) inStr = !inStr;
      if (!inStr) {
        if (c == '{' || c == '[' || c == '(') depth++;
        if (c == '}' || c == ']' || c == ')') depth--;
        if (depth == 0 && lower.startsWith(sepL, i)) {
          out.add(input.substring(start, i).trim());
          i += sep.length();
          start = i;
          continue;
        }
      }
      i++;
    }
    out.add(input.substring(start).trim());
    return out;
  }

  private static int indexOfTopLevel(String input, char sep) {
    int depth = 0;
    boolean inStr = false;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c == '"' && (i == 0 || input.charAt(i - 1) != '\\')) inStr = !inStr;
      if (!inStr) {
        if (c == '{' || c == '[' || c == '(') depth++;
        if (c == '}' || c == ']' || c == ')') depth--;
        if (c == sep && depth == 0) return i;
      }
    }
    return -1;
  }
}
