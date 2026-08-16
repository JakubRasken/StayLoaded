# StayLoaded

> **Log off. Stay loaded.**

StayLoaded is an in-development Paper plugin that will let a player disconnect while a server-side stand-in keeps their farms and local simulation active.

## Status

StayLoaded is pre-alpha. It can create development-only, random-identity synthetic player probes, but it does **not** hand off a real player yet and must not be installed on a production server.

The first supported lines are:

- Paper and Purpur `1.21.11`
- Paper and Purpur `26.2`

Folia and Spigot are not supported. Full player handoff needs Paper internals and cannot be implemented faithfully through the Bukkit API alone.

## Product Goals

- Replace a disconnecting player with a true server-side player stand-in.
- Preserve identity, inventory, position, health, effects, game mode, and ownership safely.
- Remove the stand-in before the real player completes login.
- Expire sessions after a configurable duration.
- Recover active sessions safely after an orderly restart.
- Prefer data integrity over keeping an uncertain stand-in alive.
- Keep shared behavior independent of Minecraft internals so new adapters remain small.

## Inspiration

StayLoaded is an independent implementation inspired by Sakura Ryoko's [Unplugged AFK](https://github.com/sakura-ryoko/unplugged-afk). It is not affiliated with or endorsed by that project. No Unplugged AFK source code, assets, or branding are included.

## Build

Requirements:

- JDK 25 to run Gradle and build the `26.2` adapter
- Internet access for Paper development bundles

```bash
./gradlew clean check releaseArtifacts
```

Version-specific artifacts are written under each adapter's `build/libs` directory. The `1.21.11` release jar is re-obfuscated for that runtime; the `26.2` jar is Mojang-mapped because Minecraft `26.1+` is unobfuscated.

## Architecture

- `core`: server-independent session model and policy
- `paper-common`: Paper lifecycle, commands, persistence, and adapter contract
- `paper-1_21_11`: NMS implementation and packaging for `1.21.11`
- `paper-26_2`: NMS implementation and packaging for `26.2`

See [Architecture](docs/ARCHITECTURE.md), [Roadmap](docs/ROADMAP.md), and [Launch Plan](docs/LAUNCH.md).

The opt-in probe facility is documented in [Debug Probes](docs/DEBUG_PROBES.md). It exists only to validate NMS behavior on disposable test servers.

The real-player path is documented in [Experimental Handoff](docs/EXPERIMENTAL_HANDOFF.md) and remains disabled because its crash and compatibility gates are incomplete. Active stand-ins are checkpointed periodically, and orderly-restart restoration is available as an opt-in flag, but crash recovery and full lifecycle safety are not complete.

Operators: see [Migration](docs/MIGRATION.md) and [Troubleshooting](docs/TROUBLESHOOTING.md).

## License

StayLoaded is licensed under the [Apache License 2.0](LICENSE).
