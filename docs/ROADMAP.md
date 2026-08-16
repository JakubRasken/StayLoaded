# Roadmap

Every milestone is committed and pushed only after its checks pass.

## M1: Foundation

- Reserve independent branding and repository.
- Establish shared modules and version adapters for `1.21.11` and `26.2`.
- Produce both version-specific artifacts in CI.
- Document safety invariants, compatibility matrix, and launch plan.

## M2: Session Core

- [x] Define explicit session transitions and failure reasons.
- [x] Add atomic JSON persistence with schema versioning and backup recovery.
- [x] Implement timeout policy and restart-time accounting.
- [x] Add unit tests for state transitions and persistence recovery.

## M3: Fake Player Prototype

- [x] Build synthetic connection and packet listener in both adapters.
- [x] Spawn and remove a random test-profile stand-in on the main thread.
- Prove that simulation, mob spawning, redstone, and farms treat it as a player.
- [x] Add unsupported-build rejection and diagnostic output.

## M4: Safe Player Handoff

- Implement `/stayloaded [duration] [reason]`.
- Snapshot and persist the session before disconnect.
- Transfer identity and complete player state to the stand-in.
- Remove the stand-in before the real player's duplicate-login check.
- Test inventory and identity safety in online and offline mode.

## M5: Lifecycle Hardening

- [x] Restore sessions after orderly restart (opt-in `restore-after-restart`; checkpointed stand-ins are re-spawned from their `.dat`, clean shutdowns leave nothing to restore).
- [x] Handle death (stand-ins respawn at their bed/anchor up to `max-stand-in-respawns` times; past the cap they are removed without saving and the session ends `PLAYER_DIED`, keeping the last checkpoint authoritative).
- [x] Handle respawn, dimensions (restored stand-ins go to their recorded `Dimension`), portals, and pearls (portal/pearl teleports and mounts are cancelled for stand-ins).
- [x] Add configurable safety policies and admin termination (`/stayloaded terminate <player>`, `stayloaded.terminate` permission).
- [x] Recover or fail closed after interrupted writes and crashes (atomic temp-file writes with validated `.dat_old`; `DurableFilesTest` covers crash-mid-write and temp-file recovery).

## M6: Operator Experience

- Add permissions, admin list/info/terminate/reload commands, and tab completion.
- Add configurable MiniMessage output and optional broadcasts.
- Add metrics with explicit disclosure and opt-out.
- Publish migration and troubleshooting documentation.

## M7: Public Beta

- Run closed testing on disposable servers and backed-up survival copies.
- Publish release candidates to GitHub with prominent pre-release warnings.
- Resolve all data-loss and server-crash defects before marketplace submission.
- Submit verified builds to Modrinth, Hangar, and CurseForge.

## M8: Expansion

- Add older Paper adapters based on demand and maintenance cost.
- Automate update PRs when Paper development bundles change.
- Evaluate a documented extension API after the lifecycle is stable.
- Re-evaluate Folia only if its threading model can preserve every safety invariant.
