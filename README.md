<p align="left">
  <img src="https://cdn.fliver.net/a/u/487c215c-ba91-4bbf-90ff-35e190a41fdd" width="160" />
</p>

Open-source `.fl` scripting runtime for Paper/Spigot — the language inside [Fliver Zen](https://fliver.net).

> **Documentation:** [docs.fliver.net/zen/scripting](https://docs.fliver.net/zen/scripting)

## What this repo contains

This repository ships **`net.fliver:zen-engine`** only:

- Parser (`on fliver request`, `function`, `options`)
- Runtime (`FlValue`, loops, functions, HTTP request context)
- Bukkit builtins (players, server metrics, bans, CSV storage)
- Extensible `SyntaxRegistry` for host-specific expressions

It does **not** include Fliver cloud pairing, the outbound tunnel, Insights, or the proprietary Zen plugin jar. Those stay in the private Fliver monorepo and are shaded into the distributed `Fliver-Zen-*.jar`.

## Maven

Artifacts publish to **https://fliver.net/maven**:

```xml
<dependency>
  <groupId>net.fliver</groupId>
  <artifactId>zen-engine</artifactId>
  <version>0.1.0-beta</version>
</dependency>
```

## Build

Requires JDK 8+ and Maven:

```bash
mvn -f products/zen clean package
```

Output: `target/zen-engine-0.1.0-beta.jar`

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Syntax is Skript-inspired; this is **not** a fork of [SkriptLang/Skript](https://github.com/SkriptLang/Skript) (GPL-3.0).
