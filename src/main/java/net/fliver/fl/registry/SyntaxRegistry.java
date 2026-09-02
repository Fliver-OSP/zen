package net.fliver.fl.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fliver.fl.engine.FlValue;
import net.fliver.fl.engine.ScriptContext;
import net.fliver.fl.lang.Condition;
import net.fliver.fl.lang.Effect;
import net.fliver.fl.lang.Expression;
import net.fliver.fl.lang.ScriptException;

/**
 * Central Skript-style syntax registry. Builtins and future “addons” register
 * pattern → factory mappings here so the language grows without forking
 * SkriptLang/Skript (GPL).
 */
public final class SyntaxRegistry {
  private static final SyntaxRegistry INSTANCE = new SyntaxRegistry();

  private final List<ExprEntry> expressions = new ArrayList<ExprEntry>();
  private final List<EffectEntry> effects = new ArrayList<EffectEntry>();
  private final List<CondEntry> conditions = new ArrayList<CondEntry>();
  private boolean sealed;

  private SyntaxRegistry() {}

  public static SyntaxRegistry get() {
    return INSTANCE;
  }

  public synchronized void registerExpression(String regex, ExprFactory factory) {
    ensureOpen();
    expressions.add(new ExprEntry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), factory));
  }

  public synchronized void registerEffect(String regex, EffectFactory factory) {
    ensureOpen();
    effects.add(new EffectEntry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), factory));
  }

  public synchronized void registerCondition(String regex, CondFactory factory) {
    ensureOpen();
    conditions.add(new CondEntry(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), factory));
  }

  public synchronized void seal() {
    sealed = true;
  }

  public Expression tryExpression(String text) throws ScriptException {
    String t = text.trim();
    for (ExprEntry e : expressions) {
      Matcher m = e.pattern.matcher(t);
      if (m.matches()) {
        return e.factory.create(m);
      }
    }
    return null;
  }

  public Effect tryEffect(String text) throws ScriptException {
    String t = text.trim();
    for (EffectEntry e : effects) {
      Matcher m = e.pattern.matcher(t);
      if (m.matches()) {
        return e.factory.create(m);
      }
    }
    return null;
  }

  public Condition tryCondition(String text) throws ScriptException {
    String t = text.trim();
    for (CondEntry e : conditions) {
      Matcher m = e.pattern.matcher(t);
      if (m.matches()) {
        return e.factory.create(m);
      }
    }
    return null;
  }

  private void ensureOpen() {
    if (sealed) {
      throw new IllegalStateException("SyntaxRegistry is sealed");
    }
  }

  public interface ExprFactory {
    Expression create(Matcher m) throws ScriptException;
  }

  public interface EffectFactory {
    Effect create(Matcher m) throws ScriptException;
  }

  public interface CondFactory {
    Condition create(Matcher m) throws ScriptException;
  }

  /** Helper: group text → deferred expression parse. */
  public static Expression deferred(final String raw) {
    return new Expression() {
      @Override
      public FlValue evaluate(ScriptContext ctx) throws ScriptException {
        return net.fliver.fl.expr.Expressions.parse(raw).evaluate(ctx);
      }
    };
  }

  public static String lower(String s) {
    return s == null ? "" : s.toLowerCase(Locale.ROOT);
  }

  private static final class ExprEntry {
    final Pattern pattern;
    final ExprFactory factory;

    ExprEntry(Pattern pattern, ExprFactory factory) {
      this.pattern = pattern;
      this.factory = factory;
    }
  }

  private static final class EffectEntry {
    final Pattern pattern;
    final EffectFactory factory;

    EffectEntry(Pattern pattern, EffectFactory factory) {
      this.pattern = pattern;
      this.factory = factory;
    }
  }

  private static final class CondEntry {
    final Pattern pattern;
    final CondFactory factory;

    CondEntry(Pattern pattern, CondFactory factory) {
      this.pattern = pattern;
      this.factory = factory;
    }
  }
}
