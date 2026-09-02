package net.fliver.fl.lang;

import net.fliver.fl.engine.FlValue;
import net.fliver.fl.engine.ScriptContext;

/** Something that produces a {@link FlValue} (Skript "expression"). */
public interface Expression {
  FlValue evaluate(ScriptContext ctx) throws ScriptException;
}
