package net.fliver.fl.lang;

import net.fliver.fl.engine.ScriptContext;

/** A runnable effect / statement line (Skript "effect"). */
public interface Effect {
  void execute(ScriptContext ctx) throws ScriptException;
}
