# Contributing to Zen Engine

## Adding syntax

1. Register patterns in `SyntaxRegistry` **before** `BuiltinSyntax.ensureLoaded()` seals the registry.
2. Prefer `builtins/BuiltinSyntax.java` for core surface; use a separate class (like `CsvSyntax`) for large feature groups.
3. Keep Fliver-cloud-specific expressions out of this repo — host plugins register those via `SyntaxRegistry.registerExpression(...)`.

## Tests

```bash
mvn -f products/zen test
```

Add tests under `src/test/java` for parser edge cases, `PathPattern`, and `FlValue` serialization.

## Releasing

Version bumps happen in `products/zen/pom.xml` inside the private monorepo. CI mirrors artifacts to `web/public/maven` and this public repo via `npm run push:zen`.
