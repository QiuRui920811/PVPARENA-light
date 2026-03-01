# Changelog

## 1.0.5

### Added
- `/duel leave` to exit winner-loot phase early.
- Tab completion for `leave` subcommand.
- Language keys for duel early-leave feedback.

### Changed
- Admin/setup commands are force-allowed in protected zones during setup flow.
- Command-block messages now use per-player cooldown to prevent chat spam.
- Movement lock now excludes winner-loot countdown phase.

### Fixed
- OP/admin setup flow blocked after arena creation in protected area.
- Spectator command block now allows `/duel leave`.
- Winner and loser can both trigger loot-phase early exit.
- Multiple hologram overlap cleanup hardening in rank-side implementation (deployed separately).
