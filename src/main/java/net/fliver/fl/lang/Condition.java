package net.fliver.fl.lang;

import net.fliver.fl.engine.ScriptContext;

/** Boolean test (Skript "condition"). */
public interface Condition {
  boolean check(ScriptContext ctx) throws ScriptException;
}
