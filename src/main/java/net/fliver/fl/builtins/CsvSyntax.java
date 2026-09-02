package net.fliver.fl.builtins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fliver.fl.engine.FlValue;
import net.fliver.fl.engine.ScriptContext;
import net.fliver.fl.expr.Expressions;
import net.fliver.fl.lang.Condition;
import net.fliver.fl.lang.Effect;
import net.fliver.fl.lang.Expression;
import net.fliver.fl.lang.ScriptException;
import net.fliver.fl.registry.SyntaxRegistry;
import net.fliver.fl.storage.CsvStore;

/** CSV create/read/write/query syntax for the .fl engine. */
public final class CsvSyntax {
  private static final Pattern QUOTED = Pattern.compile("^\"([^\"]*)\"$");

  private CsvSyntax() {}

  public static void register(SyntaxRegistry r) {
    registerExpressions(r);
    registerEffects(r);
    registerConditions(r);
  }

  private static void registerExpressions(SyntaxRegistry r) {
    r.registerExpression(
        "^csv rows from (.+?) where column (.+?) (is not|isn't|!=|>=|<=|contains|greater than or equal to|less than or equal to|greater than|less than|is|>|<) (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            final String column = parseColumnName(m.group(2).trim());
            final String op = m.group(3).trim().toLowerCase(Locale.ROOT);
            final Expression expected = Expressions.parse(m.group(4).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                try {
                  List<FlValue> rows =
                      CsvStore.get().filterRows(name, column, op, expected.evaluate(ctx));
                  return FlValue.ofList(rows);
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
    r.registerExpression(
        "^csv rows from (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                try {
                  return FlValue.ofList(CsvStore.get().rows(name));
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
    r.registerExpression(
        "^csv headers of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                try {
                  return CsvStore.get().headers(name);
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
    r.registerExpression(
        "^csv row count of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                try {
                  return FlValue.ofLong(CsvStore.get().rowCount(name));
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
    r.registerExpression(
        "^csv text of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                try {
                  return FlValue.ofString(CsvStore.get().text(name));
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
  }

  private static void registerEffects(SyntaxRegistry r) {
    r.registerEffect(
        "^create csv (.+?) with headers (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            final Expression headersExpr = Expressions.parse(m.group(2).trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                try {
                  List<String> headers = parseHeadersValue(headersExpr.evaluate(ctx));
                  CsvStore.get().create(name, headers);
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
    r.registerEffect(
        "^save csv (.+?) from (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            final Expression rowsExpr = Expressions.parse(m.group(2).trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                try {
                  CsvStore.get().save(name, rowsExpr.evaluate(ctx).asList());
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
    r.registerEffect(
        "^append row (.+?) to csv (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            final Expression rowExpr = Expressions.parse(m.group(1).trim());
            final String name = parseCsvName(m.group(2).trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                try {
                  CsvStore.get().appendRow(name, rowExpr.evaluate(ctx));
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
    r.registerEffect(
        "^delete csv (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                try {
                  CsvStore.get().delete(name);
                } catch (IOException e) {
                  throw new ScriptException("CSV error: " + e.getMessage(), e);
                }
              }
            };
          }
        });
  }

  private static void registerConditions(SyntaxRegistry r) {
    r.registerCondition(
        "^csv (.+?) exists$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            final String name = parseCsvName(m.group(1).trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                try {
                  return CsvStore.get().exists(name);
                } catch (ScriptException e) {
                  return false;
                }
              }
            };
          }
        });
    r.registerCondition(
        "^csv (.+?) does not exist$|^csv (.+?) doesn't exist$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final String name = parseCsvName(raw.trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                try {
                  return !CsvStore.get().exists(name);
                } catch (ScriptException e) {
                  return true;
                }
              }
            };
          }
        });
  }

  private static String parseCsvName(String raw) throws ScriptException {
    Matcher q = QUOTED.matcher(raw.trim());
    if (q.matches()) {
      return q.group(1).trim();
    }
    if (raw.startsWith("{") && raw.endsWith("}")) {
      throw new ScriptException("CSV name must be a quoted string literal, not a variable.");
    }
    return raw.trim();
  }

  private static String parseColumnName(String raw) throws ScriptException {
    Matcher q = QUOTED.matcher(raw.trim());
    if (q.matches()) {
      return q.group(1).trim();
    }
    return raw.trim();
  }

  private static List<String> parseHeadersValue(FlValue value) throws ScriptException {
    if (value.getKind() == FlValue.Kind.LIST) {
      List<String> out = new ArrayList<String>();
      for (FlValue item : value.asList()) {
        out.add(item.asString().trim());
      }
      return CsvStore.parseHeaderString(String.join(",", out));
    }
    return CsvStore.parseHeaderString(value.asString());
  }
}
