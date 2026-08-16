# Experimental Handoff

Real-player handoff exists as disabled pre-alpha code for controlled development tests. It is not safe for production worlds.

```yaml
experimental-handoff:
  enabled: false
  allow-insecure-offline-mode: false
  default-duration-seconds: 300
  max-duration-seconds: 3600
```

When enabled, `/stayloaded [duration] [reason]` disconnects the player, waits for Paper's authoritative quit save, then loads that player data into a world-only synthetic `ServerPlayer`. A synchronous pre-login listener writes and verifies the stand-in's NBT before Paper reads the returning player's file.

## Current Safety Work

- Session intent is persisted before disconnect.
- Stand-ins are added only after the real UUID leaves `PlayerList` and every world.
- The final Paper quit file, not an event-time snapshot, initializes the stand-in.
- Player NBT writes use atomic replacement, a validated `.dat_old`, and compressed-NBT readback.
- Returning login is denied when checked save or world removal cannot complete.
- Offline-mode handoff requires a separate explicit acknowledgement.

## Blocking Limitations

- Active stand-ins are not checkpointed periodically. A process crash can duplicate or lose items across separately autosaved world/player state.
- Stats and advancements are not preserved completely.
- Restart restoration is intentionally disabled.
- World-only players are absent from the tab list, Bukkit online-player collection, permissions integrations, and many plugin assumptions.
- Death, respawn, portals, vehicles, sleep, open containers, and ender-pearl ownership are not supported.
- Synchronous pre-login persistence can stall the server on a failing filesystem.
- No real-client fault-injection matrix has passed yet.

Do not enable this feature with valuable player data.
