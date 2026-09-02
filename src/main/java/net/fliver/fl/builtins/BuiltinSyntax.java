package net.fliver.fl.builtins;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import net.fliver.fl.engine.FlValue;
import net.fliver.fl.engine.ScriptContext;
import net.fliver.fl.expr.Expressions;
import net.fliver.fl.lang.Condition;
import net.fliver.fl.lang.Effect;
import net.fliver.fl.lang.Expression;
import net.fliver.fl.lang.ScriptException;
import net.fliver.fl.registry.SyntaxRegistry;
import net.fliver.fl.FlEngine;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Registers the built-in Skript-flavoured surface into {@link SyntaxRegistry}.
 * Kept separate from the parsers so the language can grow like Skript addons.
 */
public final class BuiltinSyntax {
  private static volatile boolean loaded;
  private static volatile boolean loading;

  private BuiltinSyntax() {}

  /** Player mutations must hop to the entity region on Folia. */
  private static void mutatePlayer(Player player, Runnable action) {
    if (player == null || action == null) {
      return;
    }
    FlEngine.platform().runForEntity(player, action);
  }

  public static synchronized void ensureLoaded() {
    if (loaded || loading) return;
    loading = true;
    try {
      registerAll();
      SyntaxRegistry.get().seal();
      loaded = true;
    } finally {
      loading = false;
    }
  }

  private static void registerAll() {
    SyntaxRegistry r = SyntaxRegistry.get();

    // --- Expressions ---
    r.registerExpression(
        "^unix timestamp$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) {
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) {
                return FlValue.ofLong(System.currentTimeMillis() / 1000L);
              }
            };
          }
        });
    r.registerExpression(
        "^parse(?:d)? json (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression inner = Expressions.parse(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                String s = inner.evaluate(ctx).asString();
                try {
                  return FlValue.fromJava(net.fliver.fl.util.Json.parse(s));
                } catch (RuntimeException e) {
                  return FlValue.ofNull();
                }
              }
            };
          }
        });
    r.registerExpression(
        "^sorted (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression list = Expressions.parse(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                List<FlValue> items = new ArrayList<FlValue>(list.evaluate(ctx).asList());
                java.util.Collections.sort(
                    items,
                    new java.util.Comparator<FlValue>() {
                      @Override
                      public int compare(FlValue a, FlValue b) {
                        if (a.getKind() == FlValue.Kind.NUMBER
                            || b.getKind() == FlValue.Kind.NUMBER) {
                          return Double.compare(a.asNumber(), b.asNumber());
                        }
                        return a.asString().compareToIgnoreCase(b.asString());
                      }
                    });
                return FlValue.ofList(items);
              }
            };
          }
        });
    r.registerExpression(
        "^now(?: in millis(?:econds)?)?$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) {
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) {
                return FlValue.ofLong(System.currentTimeMillis());
              }
            };
          }
        });
    r.registerExpression(
        "^a? ?random uuid$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) {
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) {
                return FlValue.ofString(UUID.randomUUID().toString());
              }
            };
          }
        });
    r.registerExpression(
        "^random (?:number )?between (.+) and (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression lo = Expressions.parse(m.group(1).trim());
            final Expression hi = Expressions.parse(m.group(2).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                double a = lo.evaluate(ctx).asNumber();
                double b = hi.evaluate(ctx).asNumber();
                double min = Math.min(a, b);
                double max = Math.max(a, b);
                return FlValue.ofNumber(min + Math.random() * (max - min));
              }
            };
          }
        });
    r.registerExpression(
        "^names? of all worlds$|^world names$|^list of worlds$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) {
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) {
                List<String> names = new ArrayList<String>();
                for (World w : Bukkit.getWorlds()) names.add(w.getName());
                return FlValue.ofStrings(names);
              }
            };
          }
        });
    r.registerExpression(
        "^number of worlds$|^world count$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) {
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) {
                return FlValue.ofLong(Bukkit.getWorlds().size());
              }
            };
          }
        });
    r.registerExpression(
        "^view distance$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) {
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) {
                try {
                  return FlValue.ofLong(Bukkit.getViewDistance());
                } catch (Throwable t) {
                  return FlValue.ofLong(0);
                }
              }
            };
          }
        });
    r.registerExpression(
        "^query arg(?:ument)?\\s+(.+)$|^param(?:eter)?\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression key = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofString(ctx.getQueryArg(key.evaluate(ctx).asString()));
              }
            };
          }
        });
    r.registerExpression(
        "^(?:fliver )?path arg(?:ument)?\\s+(.+)$|^(?:fliver )?path param(?:eter)?\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression key = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofString(ctx.getPathArg(key.evaluate(ctx).asString()));
              }
            };
          }
        });
    r.registerExpression(
        "^names? of banned players$|^banned player names$|^list of bans$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) {
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) {
                List<String> names = new ArrayList<String>();
                for (org.bukkit.BanEntry entry :
                    Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntries()) {
                  if (entry != null && entry.getTarget() != null) {
                    names.add(entry.getTarget());
                  }
                }
                return FlValue.ofStrings(names);
              }
            };
          }
        });
    r.registerExpression(
        "^ban info of (?:player )?(.+)$|^ban of (?:player )?(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression name = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return banInfoValue(name.evaluate(ctx).asString());
              }
            };
          }
        });
    r.registerExpression(
        "^ban reason of (?:player )?(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression name = Expressions.parse(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                org.bukkit.BanEntry e = banEntry(name.evaluate(ctx).asString());
                if (e == null) return FlValue.ofNull();
                return FlValue.ofString(e.getReason() == null ? "" : e.getReason());
              }
            };
          }
        });
    r.registerExpression(
        "^ban (?:date|created|creation) of (?:player )?(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression name = Expressions.parse(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                org.bukkit.BanEntry e = banEntry(name.evaluate(ctx).asString());
                if (e == null || e.getCreated() == null) return FlValue.ofNull();
                return FlValue.ofString(formatDate(e.getCreated()));
              }
            };
          }
        });
    r.registerExpression(
        "^ban (?:expiration|expiry) of (?:player )?(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression name = Expressions.parse(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                org.bukkit.BanEntry e = banEntry(name.evaluate(ctx).asString());
                if (e == null) return FlValue.ofNull();
                if (e.getExpiration() == null) return FlValue.ofString("permanent");
                return FlValue.ofString(formatDate(e.getExpiration()));
              }
            };
          }
        });
    r.registerExpression(
        "^ban source of (?:player )?(.+)$|^ban(?:ned)? by (?:player )?(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression name = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                org.bukkit.BanEntry e = banEntry(name.evaluate(ctx).asString());
                if (e == null) return FlValue.ofNull();
                return FlValue.ofString(e.getSource() == null ? "" : e.getSource());
              }
            };
          }
        });
    r.registerExpression(
        "^duration seconds from (.+)$|^parse duration (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression input = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofLong(parseDurationSeconds(input.evaluate(ctx).asString()));
              }
            };
          }
        });
    r.registerExpression(
        "^header\\s+(.+)$|^request header\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression key = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofString(ctx.getHeader(key.evaluate(ctx).asString()));
              }
            };
          }
        });
    r.registerExpression(
        "^option\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression key = Expressions.parse(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofString(ctx.getOption(key.evaluate(ctx).asString()));
              }
            };
          }
        });
    r.registerExpression(
        "^player\\s+(.+?)\\s+(?:'s\\s+)?(?:health|hp)$|^health of player\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression name = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                Player p = Bukkit.getPlayerExact(name.evaluate(ctx).asString());
                return p == null ? FlValue.ofNull() : FlValue.ofNumber(p.getHealth());
              }
            };
          }
        });
    r.registerExpression(
        "^player\\s+(.+?)\\s+(?:'s\\s+)?(?:gamemode|game mode)$|^gamemode of player\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression name = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                Player p = Bukkit.getPlayerExact(name.evaluate(ctx).asString());
                return p == null ? FlValue.ofNull() : FlValue.ofString(p.getGameMode().name());
              }
            };
          }
        });
    r.registerExpression(
        "^player\\s+(.+?)\\s+(?:'s\\s+)?world$|^world of player\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression name = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                Player p = Bukkit.getPlayerExact(name.evaluate(ctx).asString());
                return p == null ? FlValue.ofNull() : FlValue.ofString(p.getWorld().getName());
              }
            };
          }
        });
    r.registerExpression(
        "^player\\s+(.+?)\\s+(?:'s\\s+)?(?:location|coords|coordinates)$|^location of player\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression name = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                Player p = Bukkit.getPlayerExact(name.evaluate(ctx).asString());
                if (p == null) return FlValue.ofNull();
                Location loc = p.getLocation();
                java.util.LinkedHashMap<String, FlValue> map =
                    new java.util.LinkedHashMap<String, FlValue>();
                map.put("world", FlValue.ofString(loc.getWorld().getName()));
                map.put("x", FlValue.ofNumber(loc.getX()));
                map.put("y", FlValue.ofNumber(loc.getY()));
                map.put("z", FlValue.ofNumber(loc.getZ()));
                map.put("yaw", FlValue.ofNumber(loc.getYaw()));
                map.put("pitch", FlValue.ofNumber(loc.getPitch()));
                return FlValue.ofObject(map);
              }
            };
          }
        });
    r.registerExpression(
        "^absolute value of (.+)$|^abs(?:olute)?\\s+(.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression inner = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofNumber(Math.abs(inner.evaluate(ctx).asNumber()));
              }
            };
          }
        });
    r.registerExpression(
        "^uppercase (.+)$|^upper(?:case)? of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression inner = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofString(inner.evaluate(ctx).asString().toUpperCase(Locale.ROOT));
              }
            };
          }
        });
    r.registerExpression(
        "^lowercase (.+)$|^lower(?:case)? of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression inner = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofString(inner.evaluate(ctx).asString().toLowerCase(Locale.ROOT));
              }
            };
          }
        });
    r.registerExpression(
        "^substring of (.+) from (.+) to (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression text = Expressions.parse(m.group(1).trim());
            final Expression from = Expressions.parse(m.group(2).trim());
            final Expression to = Expressions.parse(m.group(3).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                String s = text.evaluate(ctx).asString();
                int a = Math.max(0, (int) from.evaluate(ctx).asLong() - 1);
                int b = Math.min(s.length(), (int) to.evaluate(ctx).asLong());
                if (a > b) return FlValue.ofString("");
                return FlValue.ofString(s.substring(a, b));
              }
            };
          }
        });
    r.registerExpression(
        "^(.+) joined with (.+)$|^join (.+) (?:with|by|using) (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String listRaw = m.group(1) != null ? m.group(1) : m.group(3);
            String sepRaw = m.group(2) != null ? m.group(2) : m.group(4);
            final Expression list = Expressions.parse(listRaw.trim());
            final Expression sep = Expressions.parse(sepRaw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                String delimiter = sep.evaluate(ctx).asString();
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (FlValue v : list.evaluate(ctx).asList()) {
                  if (!first) sb.append(delimiter);
                  first = false;
                  sb.append(v.asString());
                }
                return FlValue.ofString(sb.toString());
              }
            };
          }
        });
    r.registerExpression(
        "^split (.+) (?:by|at|with) (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression text = Expressions.parse(m.group(1).trim());
            final Expression sep = Expressions.parse(m.group(2).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                String s = text.evaluate(ctx).asString();
                String d = sep.evaluate(ctx).asString();
                List<FlValue> out = new ArrayList<FlValue>();
                if (d.isEmpty()) {
                  out.add(FlValue.ofString(s));
                } else {
                  int start = 0;
                  int idx;
                  while ((idx = s.indexOf(d, start)) >= 0) {
                    out.add(FlValue.ofString(s.substring(start, idx)));
                    start = idx + d.length();
                  }
                  out.add(FlValue.ofString(s.substring(start)));
                }
                return FlValue.ofList(out);
              }
            };
          }
        });
    r.registerExpression(
        "^replace (.+) with (.+) in (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression find = Expressions.parse(m.group(1).trim());
            final Expression repl = Expressions.parse(m.group(2).trim());
            final Expression text = Expressions.parse(m.group(3).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                String s = text.evaluate(ctx).asString();
                String a = find.evaluate(ctx).asString();
                String b = repl.evaluate(ctx).asString();
                return FlValue.ofString(a.isEmpty() ? s : s.replace(a, b));
              }
            };
          }
        });
    r.registerExpression(
        "^type of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression inner = Expressions.parse(m.group(1).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                return FlValue.ofString(inner.evaluate(ctx).typeName());
              }
            };
          }
        });
    r.registerExpression(
        "^first element of (.+)$|^first of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression list = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                List<FlValue> items = list.evaluate(ctx).asList();
                return items.isEmpty() ? FlValue.ofNull() : items.get(0);
              }
            };
          }
        });
    r.registerExpression(
        "^last element of (.+)$|^last of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression list = Expressions.parse(raw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                List<FlValue> items = list.evaluate(ctx).asList();
                return items.isEmpty() ? FlValue.ofNull() : items.get(items.size() - 1);
              }
            };
          }
        });
    r.registerExpression(
        "^index (.+) of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            final Expression index = Expressions.parse(m.group(1).trim());
            final Expression list = Expressions.parse(m.group(2).trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                List<FlValue> items = list.evaluate(ctx).asList();
                int i = (int) index.evaluate(ctx).asLong() - 1;
                if (i < 0 || i >= items.size()) return FlValue.ofNull();
                return items.get(i);
              }
            };
          }
        });
    r.registerExpression(
        "^value (.+?) of (.+)$|^field (.+?) of (.+)$",
        new SyntaxRegistry.ExprFactory() {
          @Override
          public Expression create(Matcher m) throws ScriptException {
            String keyRaw = m.group(1) != null ? m.group(1) : m.group(3);
            String objRaw = m.group(2) != null ? m.group(2) : m.group(4);
            final Expression key = Expressions.parse(keyRaw.trim());
            final Expression obj = Expressions.parse(objRaw.trim());
            return new Expression() {
              @Override
              public FlValue evaluate(ScriptContext ctx) throws ScriptException {
                FlValue object = obj.evaluate(ctx);
                if (object.getKind() != FlValue.Kind.OBJECT) {
                  return FlValue.ofNull();
                }
                String wanted = key.evaluate(ctx).asString().toLowerCase(Locale.ROOT);
                for (java.util.Map.Entry<String, FlValue> e : object.asObject().entrySet()) {
                  if (e.getKey().equalsIgnoreCase(wanted)) {
                    return e.getValue();
                  }
                }
                return FlValue.ofNull();
              }
            };
          }
        });

    CsvSyntax.register(r);

    // --- Effects ---
    r.registerEffect(
        "^execute(?: console)? command (.+)$|^run console command (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression cmd = Expressions.parse(raw.trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                String line = cmd.evaluate(ctx).asString();
                if (line.startsWith("/")) line = line.substring(1);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
              }
            };
          }
        });
    r.registerEffect(
        "^kick player (.+?) because of (.+)$|^kick player (.+?) due to (.+)$|^kick player (.+?) with reason (.+)$|^kick player (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            String nameRaw =
                m.group(1) != null
                    ? m.group(1)
                    : (m.group(3) != null
                        ? m.group(3)
                        : (m.group(5) != null ? m.group(5) : m.group(7)));
            String reasonRaw =
                m.group(2) != null
                    ? m.group(2)
                    : (m.group(4) != null ? m.group(4) : m.group(6));
            final Expression name = Expressions.parse(nameRaw.trim());
            final Expression reason =
                reasonRaw == null || reasonRaw.trim().isEmpty()
                    ? null
                    : Expressions.parse(reasonRaw.trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                Player p = Bukkit.getPlayerExact(name.evaluate(ctx).asString());
                if (p == null) return;
                final String msg =
                    reason == null
                        ? "Kicked by Fliver Zen"
                        : ChatColor.translateAlternateColorCodes(
                            '&', reason.evaluate(ctx).asString());
                mutatePlayer(
                    p,
                    new Runnable() {
                      @Override
                      public void run() {
                        p.kickPlayer(msg);
                      }
                    });
              }
            };
          }
        });
    r.registerEffect(
        "^ban player (.+?) because of (.+?) for (.+?) seconds$|^ban player (.+?) because of (.+?) for (.+?) sec(?:ond)?s?$|^ban player (.+?) for (.+?) seconds$|^ban player (.+?) for (.+?) sec(?:ond)?s?$|^ban player (.+?) because of (.+)$|^ban player (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            String nameRaw =
                m.group(1) != null
                    ? m.group(1)
                    : (m.group(4) != null
                        ? m.group(4)
                        : (m.group(7) != null
                            ? m.group(7)
                            : (m.group(9) != null
                                ? m.group(9)
                                : (m.group(11) != null ? m.group(11) : m.group(13)))));
            String reasonRaw =
                m.group(2) != null
                    ? m.group(2)
                    : (m.group(5) != null ? m.group(5) : m.group(12));
            String secondsRaw =
                m.group(3) != null
                    ? m.group(3)
                    : (m.group(6) != null ? m.group(6) : m.group(10));
            final Expression name = Expressions.parse(nameRaw.trim());
            final Expression reason =
                reasonRaw == null || reasonRaw.trim().isEmpty()
                    ? null
                    : Expressions.parse(reasonRaw.trim());
            final Expression seconds =
                secondsRaw == null || secondsRaw.trim().isEmpty()
                    ? null
                    : Expressions.parse(secondsRaw.trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                String target = name.evaluate(ctx).asString().trim();
                if (target.isEmpty()) return;
                String msg =
                    reason == null
                        ? "Banned via Fliver Zen API"
                        : ChatColor.translateAlternateColorCodes(
                            '&', reason.evaluate(ctx).asString());
                Long durationSeconds = null;
                if (seconds != null) {
                  long parsed = seconds.evaluate(ctx).asLong();
                  if (parsed > 0) durationSeconds = Long.valueOf(parsed);
                }
                applyBan(target, msg, durationSeconds);
              }
            };
          }
        });
    r.registerEffect(
        "^unban player (.+)$|^pardon player (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression name = Expressions.parse(raw.trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                applyUnban(name.evaluate(ctx).asString());
              }
            };
          }
        });
    r.registerEffect(
        "^send (.+?) to player (.+)$|^message (.+?) to (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            String msgRaw = m.group(1) != null ? m.group(1) : m.group(3);
            String nameRaw = m.group(2) != null ? m.group(2) : m.group(4);
            final Expression msg = Expressions.parse(msgRaw.trim());
            final Expression name = Expressions.parse(nameRaw.trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                Player p = Bukkit.getPlayerExact(name.evaluate(ctx).asString());
                if (p == null) return;
                final String text =
                    ChatColor.translateAlternateColorCodes('&', msg.evaluate(ctx).asString());
                mutatePlayer(
                    p,
                    new Runnable() {
                      @Override
                      public void run() {
                        p.sendMessage(text);
                      }
                    });
              }
            };
          }
        });
    r.registerEffect(
        "^set gamemode of player (.+) to (.+)$|^set player (.+?)(?:'s)? gamemode to (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            String nameRaw = m.group(1) != null ? m.group(1) : m.group(3);
            String modeRaw = m.group(2) != null ? m.group(2) : m.group(4);
            final Expression name = Expressions.parse(nameRaw.trim());
            final Expression mode = Expressions.parse(modeRaw.trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                Player p = Bukkit.getPlayerExact(name.evaluate(ctx).asString());
                if (p == null) return;
                final String modeName = mode.evaluate(ctx).asString().toUpperCase(Locale.ROOT);
                mutatePlayer(
                    p,
                    new Runnable() {
                      @Override
                      public void run() {
                        try {
                          p.setGameMode(GameMode.valueOf(modeName));
                        } catch (IllegalArgumentException ignored) {
                        }
                      }
                    });
              }
            };
          }
        });
    r.registerEffect(
        "^log (.+)$|^print (.+)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            String raw = m.group(1) != null ? m.group(1) : m.group(2);
            final Expression msg = Expressions.parse(raw.trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                Bukkit.getLogger().info("[Fliver.fl] " + msg.evaluate(ctx).asString());
              }
            };
          }
        });
    r.registerEffect(
        "^wait (.+) (?:tick|ticks)$",
        new SyntaxRegistry.EffectFactory() {
          @Override
          public Effect create(Matcher m) throws ScriptException {
            // Sync tunnel handlers must not sleep the main thread for long;
            // capped micro-wait for Skript familiarity only.
            final Expression ticks = Expressions.parse(m.group(1).trim());
            return new Effect() {
              @Override
              public void execute(ScriptContext ctx) throws ScriptException {
                long n = Math.max(0, Math.min(40, ticks.evaluate(ctx).asLong()));
                try {
                  Thread.sleep(n * 50L);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            };
          }
        });

    // --- Conditions ---
    r.registerCondition(
        "^(?:player )?(.+?) is banned$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            final Expression name = Expressions.parse(m.group(1).trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                return banEntry(name.evaluate(ctx).asString()) != null;
              }
            };
          }
        });
    r.registerCondition(
        "^(?:player )?(.+?) is not banned$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            final Expression name = Expressions.parse(m.group(1).trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                return banEntry(name.evaluate(ctx).asString()) == null;
              }
            };
          }
        });
    r.registerCondition(
        "^player (.+) is online$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            final Expression name = Expressions.parse(m.group(1).trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                return Bukkit.getPlayerExact(name.evaluate(ctx).asString()) != null;
              }
            };
          }
        });
    r.registerCondition(
        "^player (.+) is offline$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            final Expression name = Expressions.parse(m.group(1).trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                return Bukkit.getPlayerExact(name.evaluate(ctx).asString()) == null;
              }
            };
          }
        });
    r.registerCondition(
        "^player (.+) has permission (.+)$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            final Expression name = Expressions.parse(m.group(1).trim());
            final Expression perm = Expressions.parse(m.group(2).trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                Player p = Bukkit.getPlayerExact(name.evaluate(ctx).asString());
                return p != null && p.hasPermission(perm.evaluate(ctx).asString());
              }
            };
          }
        });
    r.registerCondition(
        "^(.+?) contains (.+)$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            final Expression hay = Expressions.parse(m.group(1).trim());
            final Expression needle = Expressions.parse(m.group(2).trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                FlValue h = hay.evaluate(ctx);
                FlValue n = needle.evaluate(ctx);
                if (h.getKind() == FlValue.Kind.LIST) {
                  for (FlValue item : h.asList()) {
                    if (item.equalsValue(n)) return true;
                  }
                  return false;
                }
                return h.asString().toLowerCase(Locale.ROOT)
                    .contains(n.asString().toLowerCase(Locale.ROOT));
              }
            };
          }
        });
    r.registerCondition(
        "^(.+?) (?:does not|doesn't) contain (.+)$",
        new SyntaxRegistry.CondFactory() {
          @Override
          public Condition create(Matcher m) throws ScriptException {
            final Expression hay = Expressions.parse(m.group(1).trim());
            final Expression needle = Expressions.parse(m.group(2).trim());
            return new Condition() {
              @Override
              public boolean check(ScriptContext ctx) throws ScriptException {
                FlValue h = hay.evaluate(ctx);
                FlValue n = needle.evaluate(ctx);
                if (h.getKind() == FlValue.Kind.LIST) {
                  for (FlValue item : h.asList()) {
                    if (item.equalsValue(n)) return false;
                  }
                  return true;
                }
                return !h.asString()
                    .toLowerCase(Locale.ROOT)
                    .contains(n.asString().toLowerCase(Locale.ROOT));
              }
            };
          }
        });
  }

  private static org.bukkit.BanEntry banEntry(String name) {
    if (name == null || name.trim().isEmpty()) return null;
    return Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntry(name.trim());
  }

  @SuppressWarnings("deprecation")
  private static void applyBan(String name, String reason, Long durationSeconds) {
    String target = name == null ? "" : name.trim();
    if (target.isEmpty()) return;
    String banReason =
        reason == null || reason.trim().isEmpty() ? "Banned via Fliver Zen API" : reason.trim();
    java.util.Date expires = null;
    if (durationSeconds != null && durationSeconds.longValue() > 0) {
      expires =
          new java.util.Date(System.currentTimeMillis() + durationSeconds.longValue() * 1000L);
    }
    org.bukkit.BanList banList = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
    banList.addBan(target, banReason, expires, "Fliver Zen");
    Player online = Bukkit.getPlayerExact(target);
    if (online != null) {
      final String kickMsg = banReason;
      mutatePlayer(
          online,
          new Runnable() {
            @Override
            public void run() {
              online.kickPlayer(kickMsg);
            }
          });
    }
  }

  @SuppressWarnings("deprecation")
  private static boolean applyUnban(String name) {
    String target = name == null ? "" : name.trim();
    if (target.isEmpty() || banEntry(target) == null) return false;
    Bukkit.getBanList(org.bukkit.BanList.Type.NAME).pardon(target);
    return true;
  }

  private static long parseDurationSeconds(String raw) {
    if (raw == null) return -1L;
    String text = raw.trim().toLowerCase(Locale.ROOT);
    if (text.isEmpty()
        || "permanent".equals(text)
        || "perm".equals(text)
        || "forever".equals(text)
        || "never".equals(text)
        || "0".equals(text)) {
      return -1L;
    }
    if (text.matches("^-?\\d+(\\.\\d+)?$")) {
      double n = Double.parseDouble(text);
      return n <= 0 ? -1L : (long) n;
    }
    java.util.regex.Pattern part =
        java.util.regex.Pattern.compile("(\\d+)\\s*(s|sec|secs|second|seconds|m|min|mins|minute|minutes|h|hr|hrs|hour|hours|d|day|days)");
    java.util.regex.Matcher matcher = part.matcher(text);
    long total = 0L;
    boolean matched = false;
    while (matcher.find()) {
      matched = true;
      long amount = Long.parseLong(matcher.group(1));
      String unit = matcher.group(2);
      if (unit.startsWith("s")) total += amount;
      else if (unit.startsWith("m")) total += amount * 60L;
      else if (unit.startsWith("h")) total += amount * 3600L;
      else if (unit.startsWith("d")) total += amount * 86400L;
    }
    if (matched) return total <= 0 ? -1L : total;
    try {
      double n = Double.parseDouble(text);
      return n <= 0 ? -1L : (long) n;
    } catch (NumberFormatException ignored) {
      return -1L;
    }
  }

  private static FlValue banInfoValue(String name) {
    org.bukkit.BanEntry e = banEntry(name);
    java.util.LinkedHashMap<String, FlValue> map = new java.util.LinkedHashMap<String, FlValue>();
    map.put("player", FlValue.ofString(name == null ? "" : name));
    if (e == null) {
      map.put("banned", FlValue.ofBoolean(false));
      return FlValue.ofObject(map);
    }
    map.put("banned", FlValue.ofBoolean(true));
    map.put("reason", FlValue.ofString(e.getReason() == null ? "" : e.getReason()));
    map.put(
        "created",
        e.getCreated() == null ? FlValue.ofNull() : FlValue.ofString(formatDate(e.getCreated())));
    map.put(
        "expiration",
        e.getExpiration() == null
            ? FlValue.ofString("permanent")
            : FlValue.ofString(formatDate(e.getExpiration())));
    map.put("source", FlValue.ofString(e.getSource() == null ? "" : e.getSource()));
    return FlValue.ofObject(map);
  }

  private static String formatDate(java.util.Date date) {
    java.text.SimpleDateFormat fmt =
        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
    fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    return fmt.format(date);
  }
}
