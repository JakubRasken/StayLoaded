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

- Build synthetic connection and packet listener in both adapters.
- Spawn and remove a test-profile stand-in on the main thread.
- Prove that simulation, mob spawning, redstone, and farms treat it as a player.
- Add unsupported-build rejection and diagnostic output.

## M4: Safe Player Handoff

- Implement `/stayloaded [duration] [reason]`.
- Snapshot and persist the session before disconnect.
- Transfer identity and complete player state to the stand-in.
- Remove the stand-in before the real player's duplicate-login check.
- Test inventory and identity safety in online and offline mode.

## M5: Lifecycle Hardening

- Handle timeout, damage, death, respawn, dimensions, vehicles, and pearls.
- Restore sessions after orderly restart.
- Recover or fail closed after interrupted writes and crashes.
- Add configurable safety policies and admin termination.

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
