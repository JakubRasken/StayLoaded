# Architecture

## Design Constraint

A Bukkit NPC or a plugin chunk ticket is not equivalent to a real player. StayLoaded must create a server-listed `ServerPlayer` with a synthetic connection, hand off the real player's state, and remove that stand-in before duplicate-login validation rejects the returning player. Those operations require NMS and differ by Minecraft version.

## Module Boundary

`core` owns state transitions, timeout policy, and persistence records. It must never import Bukkit, Paper, CraftBukkit, or NMS.

`paper-common` owns commands, permissions, configuration, scheduling, events, and durable session coordination. It compiles against the oldest supported Paper API and calls Minecraft internals only through `PlatformAdapter`.

Each `paper-<version>` module owns player construction, synthetic networking, player-list insertion/removal, login replacement, player-data load/save, respawn, and version-specific packaging. An adapter is selected through `ServiceLoader`, and each release jar contains exactly one adapter.

## Version Strategy

Adapters are separate artifacts rather than one reflection-heavy universal jar. This gives each Minecraft line a compile-time NMS check and makes failures explicit.

Adding a version should require:

1. Copy the nearest adapter module.
2. Point paperweight at the new development bundle.
3. Resolve compile-time NMS changes inside that module.
4. Run the full compatibility matrix.
5. Publish a separately labelled artifact.

`1.21.11` needs a re-obfuscated release jar. `26.2` uses Mojang's unobfuscated runtime and must not be re-obfuscated.

## Safety Invariants

- At most one live server player may own a player's UUID.
- The session record is forced and atomically renamed before disconnecting the client.
- All world and player-list mutations run on the server thread.
- A returning real player wins every identity conflict.
- Ambiguous restoration terminates the stand-in instead of risking inventory overwrite.
- Session transitions are idempotent and crash recovery uses atomic primary, temporary, and backup generations.
- No fake connection is exposed to code until its required listener and channel are initialized.

## Required Integration Matrix

- Online-mode and offline-mode UUIDs
- Empty and full inventories, armor, offhand, ender chest, XP, effects, and advancements
- Overworld, Nether, End, portals, beds, vehicles, leashes, and ender-pearl stasis
- Damage, death, respawn, starvation, void, and totems
- Manual timeout, real-player return, kick, shutdown, and crash recovery
- Vanilla farms, mob spawning, random ticks, redstone, and chunk boundaries
- Common player-list, permissions, vanish, tab-list, and monitoring plugins
