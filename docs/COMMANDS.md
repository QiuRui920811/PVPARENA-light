# PvPArena Commands and Permissions

## Player Commands (no permission by default)
- `/pvp` — Open the main PvP queue menu.
- `/duel <player|accept <name>|cancel [name]|result>` — Challenge a player, accept a request, cancel a pending duel, or open the duel result menu.
- `/pk` — Toggle PK mode (only works when `pk.enabled` is true in config.yml).

## Admin Commands (require `pvparena.admin`)
- `/pvparena` (alias `/paparena`)
  - `help` — Show admin help.
  - `addkit <id>` — Save your current inventory/armor/effects as a kit.
  - `delkit <id>` — Delete a saved kit.
  - `create <id>` — Create an arena using your current wand selection bounds.
  - `delarena <id>` — Delete an arena.
  - `setspawn <id> <1|2>` — Set arena spawn point 1 or 2 to your location.
  - `reload` — Reload configs and settings.
- `/arena`
  - `create <id> [world]` — Register an arena; spawns are auto-set to your feet.
  - `setspawn <id> <1|2>` — Set a spawn point to your location.
  - `setbounds <id> <min|max|wand|minx miny minz maxx maxy maxz>` — Set bounds from current location, wand selection, or explicit coordinates.

## Permissions
- `pvparena.admin` (default: op) — Grants all admin commands above and use of the selection tool (wooden axe by default).
- Player commands `/pvp`, `/duel`, `/pk` are open to players by default; if you need to lock them down, configure permissions in your permission plugin.
