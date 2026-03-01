# PvPArena-Light

Advanced PvP practice plugin for Paper/Folia with duel flow, queue matching, arena rollback, spectator tools, and configurable mode kits.

## Highlights

- Queue + direct duel workflows (`/pvp`, `/duel`)
- Multi-mode arena settings with per-mode kits
- Winner loot phase + early leave (`/duel leave`)
- Spectator browser, hotbar tools, and state isolation
- Arena rollback and crash-recovery safeguards
- Optional integrations: ProtocolLib, PlaceholderAPI, HuskSync

## Compatibility

- Java 17+
- Paper/Folia 1.21.x
- Maven build (artifact: `pvparena-light-1.0.5.jar`)

## Installation

1. Download build artifact from Actions or Releases.
2. Place jar in server `plugins/`.
3. Start server once to generate defaults.
4. Configure files under `plugins/PvPArena-Light/`.

## Quick Setup

1. Create arena: `/arena create <id> [world]`
2. Set spawns: `/arena setspawn <id> <1|2>`
3. Set bounds: `/arena setbounds <id> wand` (or coordinate mode)
4. Open menu: `/pvp`

## Commands

See [docs/COMMANDS.md](docs/COMMANDS.md).

## Build

```bash
mvn -DskipTests package
```

Output:

- `target/pvparena-light-1.0.5.jar`

## Project Structure

- `src/main/java` plugin source
- `src/main/resources` default config/messages/plugin.yml
- `docs` operator/admin docs

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
