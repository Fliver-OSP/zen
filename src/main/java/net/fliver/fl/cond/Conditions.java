package net.fliver.fl.cond;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fliver.fl.builtins.BuiltinSyntax;
import net.fliver.fl.engine.FlValue;
import net.fliver.fl.engine.ScriptContext;
import net.fliver.fl.expr.Expressions;
import net.fliver.fl.lang.Condition;
import net.fliver.fl.lang.Expression;
import net.fliver.fl.lang.ScriptException;
import net.fliver.fl.registry.SyntaxRegistry;

public final class Conditions {
  private static final Pattern IF_HEADER = Pattern.compile("(?i)^if\\s+(.+?):$");
  private static final Pattern ELSE_IF_HEADER = Pattern.compile("(?i)^else\\s+if\\s+(.+?):$");
  private static final Pattern CHANCE =
      Pattern.compile("(?i)^chance\\s+of\\s+(\\d+(?:\\.\\d+)?)\\s*%?$");
  private static final Pattern IS_SET =
      Pattern.compile("(?i)^(\\{[^}]+\\})\\s+is\\s+set$");
  private static final Pattern IS_NOT_SET =
      Pattern.compile("(?i)^(\\{[^}]+\\})\\s+is\\s+not\\s+set$");
  private static final Pattern IS_EMPTY =
      Pattern.compile("(?i)^(.+?)\\s+is\\s+empty$");
  private static final Pattern IS_NOT_EMPTY =
      Pattern.compile("(?i)^(.+?)\\s+is\\s+not\\s+empty$");
  private static final Random RANDOM = new Random();

  private Conditions() {}

  public static String stripIfHeader(String trimmed) throws ScriptException {
    Matcher m = IF_HEADER.matcher(trimmed.trim());
    if (!m.matches()) throw new ScriptException("Not an if-header: " + trimmed);
    return m.group(1).trim();
  }

  public static String stripElseIfHeader(String trimmed) throws ScriptException {
    Matcher m = ELSE_IF_HEADER.matcher(trimmed.trim());
    if (!m.matches()) throw new ScriptException("Not an else-if-header: " + trimmed);
    return m.group(1).trim();
  }

  public static boolean isElseIfHeader(String trimmed) {
    return ELSE_IF_HEADER.matcher(trimmed.trim()).matches();
  }

  public static Condition parse(String raw) throws ScriptException {
    BuiltinSyntax.ensureLoaded();
    String text = raw.trim();
    if (text.endsWith(":")) text = text.substring(0, text.length() - 1).trim();
    if (text.toLowerCase(Locale.ROOT).startsWith("if ")) {
      text = text.substring(3).trim();
    }
    if (text.toLowerCase(Locale.ROOT).startsWith("else if ")) {
      text = text.substring(8).trim();
    }

    return parseOr(text);
  }

  private static Condition parseOr(String text) throws ScriptException {
    List<String> parts = splitLogical(text, " or ");
    if (parts.size() == 1) return parseAnd(parts.get(0));
    final List<Condition> conditions = new ArrayList<Condition>();
    for (String p : parts) conditions.add(parseAnd(p));
    return new Condition() {
      @Override
      public boolean check(ScriptContext ctx) throws ScriptException {
        for (Condition c : conditions) {
          if (c.check(ctx)) return true;
        }
        return false;
      }
    };
  }

  private static Condition parseAnd(String text) throws ScriptException {
    List<String> parts = splitLogical(text, " and ");
    if (parts.size() == 1) return parseUnary(parts.get(0));
    final List<Condition> conditions = new ArrayList<Condition>();
    for (String p : parts) conditions.add(parseUnary(p));
    return new Condition() {
      @Override
      public boolean check(ScriptContext ctx) throws ScriptException {
        for (Condition c : conditions) {
          if (!c.check(ctx)) return false;
        }
        return true;
      }
    };
  }

  private static Condition parseUnary(String text) throws ScriptException {
    String t = text.trim();
    if (t.toLowerCase(Locale.ROOT).startsWith("not ")) {
      final Condition inner = parseUnary(t.substring(4).trim());
      return new Condition() {
        @Override
        public boolean check(ScriptContext ctx) throws ScriptException {
          return !inner.check(ctx);
        }
      };
    }
    if (t.startsWith("(") && t.endsWith(")")) {
      return parseOr(t.substring(1, t.length() - 1).trim());
    }
    return parseAtom(t);
  }

  private static Condition parseAtom(String text) throws ScriptException {
    Condition registered = SyntaxRegistry.get().tryCondition(text);
    if (registered != null) return registered;

    Matcher chance = CHANCE.matcher(text);
    if (chance.matches()) {
      final double pct = Double.parseDouble(chance.group(1));
      return new Condition() {
        @Override
        public boolean check(ScriptContext ctx) {
          return RANDOM.nextDouble() * 100.0 < pct;
        }
      };
    }

    Matcher isSet = IS_SET.matcher(text);
    if (isSet.matches()) {
      final String name = isSet.group(1);
      return new Condition() {
        @Override
        public boolean check(ScriptContext ctx) {
          return ctx.hasVariable(name) && !ctx.getVariable(name).isNull();
        }
      };
    }

    Matcher isNotSet = IS_NOT_SET.matcher(text);
    if (isNotSet.matches()) {
      final String name = isNotSet.group(1);
      return new Condition() {
        @Override
        public boolean check(ScriptContext ctx) {
          return !ctx.hasVariable(name) || ctx.getVariable(name).isNull();
        }
      };
    }

    Matcher isEmpty = IS_EMPTY.matcher(text);
    if (isEmpty.matches()) {
      final Expression expr = Expressions.parse(isEmpty.group(1).trim());
      return new Condition() {
        @Override
        public boolean check(ScriptContext ctx) throws ScriptException {
          FlValue v = expr.evaluate(ctx);
          if (v.isNull()) return true;
          if (v.getKind() == FlValue.Kind.LIST) return v.asList().isEmpty();
          if (v.getKind() == FlValue.Kind.OBJECT) return v.asObject().isEmpty();
          if (v.getKind() == FlValue.Kind.STRING) return v.asString().isEmpty();
          return false;
        }
      };
    }

    Matcher isNotEmpty = IS_NOT_EMPTY.matcher(text);
    if (isNotEmpty.matches()) {
      final Expression expr = Expressions.parse(isNotEmpty.group(1).trim());
      return new Condition() {
        @Override
        public boolean check(ScriptContext ctx) throws ScriptException {
          FlValue v = expr.evaluate(ctx);
          if (v.isNull()) return false;
          if (v.getKind() == FlValue.Kind.LIST) return !v.asList().isEmpty();
          if (v.getKind() == FlValue.Kind.OBJECT) return !v.asObject().isEmpty();
          if (v.getKind() == FlValue.Kind.STRING) return !v.asString().isEmpty();
          return true;
        }
      };
    }

    final Expression expr = Expressions.parse(text);
    return new Condition() {
      @Override
      public boolean check(ScriptContext ctx) throws ScriptException {
        return expr.evaluate(ctx).asBoolean();
      }
    };
  }

  private static List<String> splitLogical(String input, String sep) {
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
        if (c == '(' || c == '{' || c == '[') depth++;
        if (c == ')' || c == '}' || c == ']') depth--;
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
}
