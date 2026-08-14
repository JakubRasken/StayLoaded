# Debug Probes

Debug probes validate that a version adapter can construct, tick, and remove a synthetic `ServerPlayer`. They use random identities, are not registered as online players, and do not implement real-player handoff.

Do not enable probes on a production server.

## Enable

Set this in `plugins/StayLoaded/config.yml` and restart the server:

```yaml
debug-probes-enabled: true
```

## Commands

Permission: `stayloaded.admin.debug` (operators by default)

```text
/stayloaded-debug probe spawn
/stayloaded-debug probe spawn <world> <x> <y> <z>
/stayloaded-debug probe remove <uuid>
/stayloaded-debug probe count
/stayloaded-debug probe clear
```

The first spawn form uses the executing player's location. The coordinate form also works from the server console.

## Isolation

A probe is inserted directly into its `ServerLevel`, not through `PlayerList.placeNewPlayer`. It therefore does not fire normal join/quit events, enter the tab list, or use normal player-list saving. Outbound packets are completed and discarded by its synthetic connection, and the nonexistent client's load timeout is suppressed.

Probes are still entities. They fire Paper entity add/remove events and may cause ordinary world, chunk, spawning, or player-simulation events. Test compatibility with other plugins only on disposable servers.

The `ServerPlayer` constructor still initializes random-UUID stats and advancement handlers. Never use a real player's UUID or name for a debug probe.

## Verified Matrix

| Paper | Java | Result |
|---|---:|---|
| `1.21.11-132` | 21 | Spawn, `count=1`, clear, `count=0`, clean shutdown |
| `26.2-112` | 25 | Spawn, `count=1`, clear, `count=0`, clean shutdown |
