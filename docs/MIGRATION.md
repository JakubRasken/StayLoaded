# Migration Guide

How to move a server onto StayLoaded, or between StayLoaded versions,
without risking player data.

## Installing

1. Choose the jar for your server line: `*mc1.21.11.jar` for Paper/Purpur
   1.21.11, `*mc26.2.jar` for 26.2. The plugin refuses to load on any other
   version (fail-closed).
2. Stop the server, place the jar in `plugins/`, and start it once so the
   default `config.yml` is created.
3. Read `plugins/StayLoaded/config.yml`. Leave
   `experimental-handoff.enabled: false` until you have run the compatibility
   checks below.

## First Enablement Checklist

On a disposable test server with a backed-up world copy:

1. `experimental-handoff.enabled: true`, everything else at defaults.
2. Join as a test player, run `/stayloaded 2m`, confirm the disconnect
   message and the broadcast (if enabled).
3. Confirm farms/redstone keep running around the stand-in.
4. Rejoin within the window; confirm inventory, position, health, and
   effects match the checkpoint.
5. Kill the stand-in (lava, void) and confirm the session ends with the last
   checkpoint intact.
6. `stop` the server and restart with `restore-after-restart: true`; confirm
   the stand-in returns for the remaining session time.
7. Repeat once with `online-mode=false` only after setting
   `allow-insecure-offline-mode: true`, understanding the UUID risk.

Only then enable handoff on a world you care about.

## Upgrading the Plugin

1. Read the release notes for behavior changes (config keys, commands,
   permissions).
2. Back up `plugins/StayLoaded/` (config, `sessions.json`, `metrics.json`).
3. Stop the server, replace the jar, and start. The plugin reconciles
   persisted sessions on boot:
   - Clean shutdown → all sessions are terminal, nothing happens.
   - Crash + `restore-after-restart` → checkpointed ACTIVE sessions are
     re-spawned; everything else ends `RECOVERY_FAILED`.
4. Run `/stayloaded list` and `/stayloaded metrics` to confirm the upgrade.

`sessions.json` uses schema version 1; unknown or newer schema files are
rejected rather than silently rewritten. Old files without newer optional
fields (like `checkpointedAt` or `respawnCount`) load with safe defaults.

## Rolling Back

1. Stop the server.
2. Restore the previous jar and the backed-up `plugins/StayLoaded/` folder.
3. If a session is mid-flight, terminate it (`/stayloaded terminate <player>`)
   or let it expire; the player's `.dat` in the world's `players` folder is
   authoritative at all times.
4. Start the server and verify the player can join.

## Uninstalling

1. Terminate any active sessions (`/stayloaded terminate <player>` for each,
   or delete `sessions.json` with the server stopped).
2. Stop the server and remove the jar.
3. Optional: delete `plugins/StayLoaded/`. Player data is not stored there —
   it lives in the world's `players` folder as normal.

## Compatibility

- Paper and Purpur, versions 1.21.11 and 26.2 only.
- Folia and Spigot are unsupported and refused.
- Other plugins that assume every online player is a real client (tab list,
  permissions, chat, economy) will not see world-only stand-ins.
