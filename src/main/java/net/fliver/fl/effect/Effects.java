package net.fliver.fl.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fliver.fl.engine.FlValue;
import net.fliver.fl.engine.ScriptContext;
import net.fliver.fl.expr.Expressions;
import net.fliver.fl.lang.Effect;
import net.fliver.fl.lang.Expression;
import net.fliver.fl.lang.ScriptException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

/** Parses effect lines (set / respond / status / broadcast / list ops). */
public final class Effects {
  private static final Pattern SET_VAR =
      Pattern.compile("(?i)^set\\s+(\\{[^}]+\\})\\s+to\\s+(.+)$");
  private static final Pattern SET_RESPONSE =
      Pattern.compile(
          "(?i)^set\\s+(?:the\\s+)?(?:fliver[- ]?)?response\\s+to\\s+(.+)$");
  private static final Pattern RESPOND =
      Pattern.compile("(?i)^respond\\s+with\\s+(.+)$");
  private static final Pattern SET_STATUS =
      Pattern.compile("(?i)^set\\s+(?:the\\s+)?(?:http\\s+)?status(?:\\s+code)?\\s+to\\s+(.+)$");
  private static final Pattern BROADCAST =
      Pattern.compile("(?i)^broadcast\\s+(.+)$");
  private static final Pattern ADD_TO =
      Pattern.compile("(?i)^add\\s+(.+?)\\s+to\\s+(\\{[^}]+\\})$");
  private static final Pattern REMOVE_FROM =
      Pattern.compile("(?i)^remove\\s+(.+?)\\s+from\\s+(\\{[^}]+\\})$");
  private static final Pattern DELETE_VAR =
      Pattern.compile("(?i)^(?:delete|clear|unset)\\s+(\\{[^}]+\\})$");
  private static final Pattern STOP =
      Pattern.compile("(?i)^(?:stop|exit|return)(?:\\s+script)?$");
  private static final Pattern PUT =
      Pattern.compile(
          "(?i)^put\\s+(.+?)\\s+in\\s+(?:the\\s+)?response(?:\\s+object)?\\s+as\\s+(.+)$");

  private Effects() {}

  public static Effect parse(String raw) throws ScriptException {
    net.fliver.fl.builtins.BuiltinSyntax.ensureLoaded();
    String text = raw.trim();
    if (text.isEmpty()) throw new ScriptException("Empty effect.");

    Effect registered = net.fliver.fl.registry.SyntaxRegistry.get().tryEffect(text);
    if (registered != null) return registered;

    if (STOP.matcher(text).matches()) {
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) {
          ctx.requestStop();
        }
      };
    }

    Matcher setVar = SET_VAR.matcher(text);
    if (setVar.matches()) {
      final String var = setVar.group(1);
      final Expression value = Expressions.parse(setVar.group(2));
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) throws ScriptException {
          ctx.setVariable(var, value.evaluate(ctx));
        }
      };
    }

    Matcher setResp = SET_RESPONSE.matcher(text);
    if (setResp.matches()) {
      final Expression value = Expressions.parse(setResp.group(1));
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) throws ScriptException {
          ctx.setResponse(value.evaluate(ctx));
        }
      };
    }

    Matcher respond = RESPOND.matcher(text);
    if (respond.matches()) {
      final Expression value = Expressions.parse(respond.group(1));
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) throws ScriptException {
          ctx.setResponse(value.evaluate(ctx));
        }
      };
    }

    Matcher status = SET_STATUS.matcher(text);
    if (status.matches()) {
      final Expression value = Expressions.parse(status.group(1));
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) throws ScriptException {
          ctx.setStatus((int) value.evaluate(ctx).asLong());
        }
      };
    }

    Matcher broadcast = BROADCAST.matcher(text);
    if (broadcast.matches()) {
      final Expression value = Expressions.parse(broadcast.group(1));
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) throws ScriptException {
          String msg =
              ChatColor.translateAlternateColorCodes('&', value.evaluate(ctx).asString());
          Bukkit.broadcastMessage(msg);
        }
      };
    }

    Matcher addTo = ADD_TO.matcher(text);
    if (addTo.matches()) {
      final Expression value = Expressions.parse(addTo.group(1));
      final String listVar = addTo.group(2);
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) throws ScriptException {
          FlValue existing = ctx.getVariable(listVar);
          List<FlValue> list = new ArrayList<FlValue>();
          if (existing.getKind() == FlValue.Kind.LIST) {
            list.addAll(existing.asList());
          } else if (!existing.isNull()) {
            list.add(existing);
          }
          list.add(value.evaluate(ctx));
          ctx.setVariable(listVar, FlValue.ofList(list));
        }
      };
    }

    Matcher removeFrom = REMOVE_FROM.matcher(text);
    if (removeFrom.matches()) {
      final Expression value = Expressions.parse(removeFrom.group(1));
      final String listVar = removeFrom.group(2);
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) throws ScriptException {
          FlValue existing = ctx.getVariable(listVar);
          if (existing.getKind() != FlValue.Kind.LIST) return;
          FlValue target = value.evaluate(ctx);
          List<FlValue> list = new ArrayList<FlValue>();
          for (FlValue item : existing.asList()) {
            if (!item.equalsValue(target)) list.add(item);
          }
          ctx.setVariable(listVar, FlValue.ofList(list));
        }
      };
    }

    Matcher deleteVar = DELETE_VAR.matcher(text);
    if (deleteVar.matches()) {
      final String var = deleteVar.group(1);
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) {
          ctx.deleteVariable(var);
        }
      };
    }

    Matcher put = PUT.matcher(text);
    if (put.matches()) {
      final Expression value = Expressions.parse(put.group(1));
      final Expression keyExpr = Expressions.parse(put.group(2));
      return new Effect() {
        @Override
        public void execute(ScriptContext ctx) throws ScriptException {
          FlValue key = keyExpr.evaluate(ctx);
          FlValue current = ctx.getResponse();
          java.util.LinkedHashMap<String, FlValue> map =
              new java.util.LinkedHashMap<String, FlValue>();
          if (current != null && current.getKind() == FlValue.Kind.OBJECT) {
            map.putAll(current.asObject());
          }
          map.put(key.asString(), value.evaluate(ctx));
          ctx.setResponse(FlValue.ofObject(map));
        }
      };
    }

    throw new ScriptException("Unknown effect: " + text);
  }

  public static boolean isBlockHeader(String trimmed) {
    String t = trimmed.toLowerCase(Locale.ROOT);
    return t.startsWith("if ")
        || t.startsWith("else if ")
        || t.startsWith("else")
        || t.startsWith("loop ");
  }
}
