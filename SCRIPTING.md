# Fliver .fl language (Skript-inspired architecture — open-source Zen engine, not GPL Skript)

> **Documentation:** [docs.fliver.net/zen/scripting](https://docs.fliver.net/zen/scripting)

Engine layout (open-source `net.fliver:zen-engine`, package `net.fliver.fl`):
- `registry/SyntaxRegistry` — pattern → factory registry (addon-ready)
- `builtins/BuiltinSyntax` — built-in expressions / effects / conditions
- `engine` — `FlValue`, `ScriptContext`, `StatementCompiler`, `ScriptRuntime`
- `FlParser` — file structure: `options`, `function`, `on fliver request`

## File structure
```
options:
    prefix: "Fliver"

function hours_label(h):
    return "%{h}% hours"

on fliver request "status" with method "GET":
    set {_hours} to hours the server has been online
    set fliver response to json {"hours": {_hours}}
```

## Triggers
- `on fliver request "path":`
- `on fliver request "path" with method "GET":`
- `on fliver request "path" with methods "GET,POST":`
- Path params: `on fliver request "bans/{player}":` → `/bans/Steve`
  - `path arg "player"` / `{player}` / `{_player}`

## Effects
- `set {_var} to <expr>` / `set fliver response to <expr>` / `respond with <expr>`
- `set status to <number>` / `put <expr> in response as "key"`
- `broadcast "…"`, `send "…" to player "Name"`, `log "…"`
- `execute console command "say hi"` / `run console command "…"`
- `kick player "Name" because of "bye"`
- `ban player "Name" because of "reason" for 3600 seconds` / `ban player "Name" because of "reason"` / `unban player "Name"`
- `duration seconds from "1h30m"` / `parse duration "7d"`
- `set gamemode of player "Name" to "CREATIVE"`
- `add <item> to {_list}` / `remove <item> from {_list}` (list ops only), `delete {_var}`, `stop`, `return <expr>`
- `call myFunc({_a}, {_b})` or expression form `myFunc({_a})`
- `wait 5 ticks` (capped; avoid long waits on request thread)
- `break` / `exit loop`, `continue` / `skip`

## Expressions
- Players: `player count`, `names of all players`, health/gamemode/world/location of player
- Server: `max players`, `motd`, `server name`, `bukkit version`, worlds, `view distance`
- Uptime: `hours the server has been online`, minutes/seconds/`uptime`
- Resources: `total ram in gb`, `allocated ram in gb`, `used ram in gb`, `server disk usage in gb`
- HTTP: `request method`, `request body`, `request query`, `query arg "page"`, `path arg "player"`, `header "Authorization"`
- Bans: `names of banned players`, `ban info of {_name}`, `ban reason/date/expiration/source of {_name}`
- Meta: `endpoint`, `project slug`, `organization slug`, `option "prefix"`
- Time: `unix timestamp`, `now`, `now in milliseconds`, `a random uuid`, `random number between 1 and 10`
- Strings/lists: uppercase/lowercase, substring, split, join, replace, first/last/index, size of, `first element of {_list}`
- Math: `+ - * / %` — use `set {_x} to {_x} + 1` for numbers (`add … to` appends to lists)
- Rounding: `floor value of`, `rounded value of`, `ceil value of`, `abs value of` (split steps when combining with `/` or `*`)
- Ternary: `if player count > 0 then "yes" else "no"`
- `parse json {_body}`, `sorted {_list}`
- `join {_list} with ", "` / `{_list} joined with ", "`
- `json {"k": {_v}}` / `json of "k" = {_v} and ...`
- `value "key" of {_obj}` / `field "key" of loop-value`

## CSV storage
Server-local CSV files under `plugins/Fliver-Zen/csv/` (max 10k rows, 1 MB per file).

Effects:
- `create csv "name" with headers "a,b,c"`
- `save csv "name" from {_rows}`
- `append row {_row} to csv "name"`
- `delete csv "name"`

Expressions:
- `csv rows from "name"`
- `csv rows from "name" where column "col" is {_v}` / `where column "col" > 100` / `contains`
- `csv headers of "name"`, `csv row count of "name"`, `csv text of "name"`

Conditions: `csv "name" exists`, `csv "name" does not exist`

See bundled `csv-registry.fl` for a full example.

## Conditions
- comparisons, `{_v} is set`, empty checks, `chance of 25%`
- `player "X" is online`, `player "X" has permission "…"`, `{_name} is banned`
- `contains` / `doesn't contain`
- `and` / `or` / `not` / parentheses

## Example — ban API
```
on fliver request "bans/{player}/ban" with methods "GET,POST":
    set {_reason} to query arg "reason"
    if query arg "reason" is empty:
        set {_reason} to "Banned via Fliver API"
    set {_seconds} to duration seconds from query arg "duration"
    if {_seconds} > 0:
        ban player path arg "player" because of {_reason} for {_seconds} seconds
    else:
        ban player path arg "player" because of {_reason}
    set fliver response to ban info of path arg "player"
```

## Control flow
`if` / `else if` / `else`, `while`, `loop all players`, `loop {_list}`, `loop N times`, functions

## Example — status
```
on fliver request "status" with method "GET":
    set {_hours} to hours the server has been online
    set fliver response to json {"hours": {_hours}}
```
