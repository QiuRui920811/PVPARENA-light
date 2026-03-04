# Changelog

## 1.1.0

### Changed
- Replaced snapshot-style arena rollback with diff-based `ArenaChangeRecorder` + chunk task queue architecture.
- Rollback execution now applies blocks in chunk-region batches with per-tick and per-nanos limits for smoother server frame times.
- Baseline initialization is decoupled from duel start critical path to avoid countdown/start blocking.

### Fixed
- Corrected placement capture to store original block state before change, preventing placed blocks from remaining after rollback.
- Expanded fluid rollback coverage with `BlockFormEvent` and `SpongeAbsorbEvent` tracking.
- Added per-round transient cleanup for dropped items/experience orbs to reduce round-to-round residue.

## 1.0.9

### Changed
- Match startup flow is staged across ticks to reduce same-tick load spikes.
- Arena preheat is moved to startup warmup queue instead of running on duel accept.
- Countdown start now waits for pre-fight preparation completion for smoother 5->4 transition.

### Fixed
- Explosion block edits are correctly allowed during round-resolving window.
- Loser/player post-match state restoration now prevents being left in adventure mode.
- Baseline warmup queue now pauses while active matches are running to avoid contention.

## 1.0.7

### Changed
- Version bump for release packaging and metadata alignment.

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
