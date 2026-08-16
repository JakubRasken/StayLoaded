# Experimental Handoff

Real-player handoff exists as disabled pre-alpha code for controlled development tests. It is not safe for production worlds.

```yaml
experimental-handoff:
  enabled: false
  allow-insecure-offline-mode: false
  default-duration-seconds: 300
  max-duration-seconds: 3600
  checkpoint-interval-seconds: 60
  restore-after-restart: false
```

When enabled, `/stayloaded [duration] [reason]` disconnects the player, waits for Paper's authoritative quit save, then loads that player data into a world-only synthetic `ServerPlayer`. A synchronous pre-login listener writes and verifies the stand-in's NBT before Paper reads the returning player's file.

## Current Safety Work

- Session intent is persisted before disconnect.
- Stand-ins are added only after the real UUID leaves `PlayerList` and every world.
- The final Paper quit file, not an event-time snapshot, initializes the stand-in.
- Player NBT writes use atomic replacement, a validated `.dat_old`, and compressed-NBT readback.
- Returning login is denied when checked save or world removal cannot complete.
- Offline-mode handoff requires a separate explicit acknowledgement.

## Checkpointing and Restart Restoration

Active stand-ins write their player data back to disk every
`checkpoint-interval-seconds` (default 60, `0` disables). Each write reuses the
same atomic-replacement, `.dat_old` backup, and compressed read-back as a
final save, so the interval only narrows the window between world autosave and
player-data save after a process crash. The session record stores the last
checkpoint time so recovery can see how fresh the stand-in's data is.

On an orderly shutdown the coordinator checkpoint every active stand-in one
final time and writes a `shutdown-clean.marker` only after every checkpoint and
session write succeeded. On the next start, if `restore-after-restart` is
enabled and that marker is absent (a crash, or a shutdown that could not
finish), an ACTIVE session whose data was checkpointed is re-spawned from its
`.dat` file; sessions that never checkpointed, and STARTING or RETURNING
sessions, are terminated as `RECOVERY_FAILED` rather than guessed at. A clean
shutdown leaves only terminal sessions in the store, so nothing is restored.

## Blocking Limitations

- Periodic checkpointing narrows, but does not close, the crash window: a
  process crash can still duplicate or lose items between world autosave and
  the most recent player-data checkpoint.
- Restart restoration covers orderly restarts; crash recovery only restores
  stand-ins that had checkpointed data, and world state itself (mobs, redstone,
  chunks) is never recreated.
- Stats and advancements are not preserved completely.
- World-only players are absent from the tab list, Bukkit online-player collection, permissions integrations, and many plugin assumptions.
- Death, respawn, portals, vehicles, sleep, open containers, and ender-pearl ownership are not supported.
- Synchronous pre-login persistence can stall the server on a failing filesystem.
- No real-client fault-injection matrix has passed yet.

Do not enable this feature with valuable player data.
