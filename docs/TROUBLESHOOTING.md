# StayLoaded Troubleshooting

Operator-facing guidance for the experimental handoff. StayLoaded is
pre-alpha; when in doubt, prefer losing a session over risking player data.

## Diagnosis Flow

1. Run `/stayloaded list` and `/stayloaded info <player>` to see sessions.
2. Check `plugins/StayLoaded/sessions.json` and `metrics.json`.
3. Read the latest server log lines that mention `StayLoaded`.
4. Reproduce on a disposable server with `debug-probes-enabled: true`.

## Symptoms

### The command says "StayLoaded could not start safely"

The plugin refuses the handoff and prints a reason, including:

- **"Experimental handoff is disabled in config.yml"** — set
  `experimental-handoff.enabled: true` and run `/stayloaded reload` (or
  restart).
- **"Offline-mode handoff requires explicit insecure-mode acknowledgement"** —
  the server is in offline mode; set
  `experimental-handoff.allow-insecure-offline-mode: true` only after
  understanding the UUID-spoofing risk.
- **"Leave death, sleep, or vehicles before starting"** — the player is in a
  state the handoff cannot transfer yet.
- **"This player already has a StayLoaded session"** — end or expire it
  first with `/stayloaded terminate <player>`.

### A player cannot rejoin ("StayLoaded is reconciling your session")

A session exists for that UUID but the stand-in could not be saved. Wait a
few seconds and try again. If it persists, terminate the session with
`/stayloaded terminate <player>`; the last checkpoint remains the
authoritative player data.

### The stand-in vanished after a restart

- If the server shut down cleanly, sessions end by design (nothing is
  restored).
- After a crash, only ACTIVE sessions that had checkpointed data are
  restored, and only when `experimental-handoff.restore-after-restart: true`.
- Sessions that were still STARTING when the server died are never restored.

### Duplicated or missing items after a crash

The plugin writes player data atomically (temp file + validated `.dat_old`
backup + readback), but the world is saved separately by the server. A
crash can still leave world state ahead of (or behind) the player data.
Keep `checkpoint-interval-seconds` low for valuable worlds and always back
up the world before relying on handoff.

### Metrics show nothing

`/stayloaded metrics` only reports events that occurred while metrics were
enabled (`metrics.enabled: true`, the default). The file is written on
session events and shutdown.

## Safe Recovery

1. Stop the server.
2. Back up the world folder and `plugins/StayLoaded/`.
3. Inspect `sessions.json`; terminal sessions are safe to ignore.
4. If `sessions.json` shows a non-terminal session and you want to abandon
   it, delete `sessions.json` (and `shutdown-clean.marker`) before starting —
   the last `.dat` checkpoint in the world's `players` folder is what the
   player gets.
5. Restart and confirm the player can join and their inventory is intact.

## Escalation

Open an issue at https://github.com/JakubRasken/StayLoaded with:

- Minecraft version and server software (Paper or Purpur)
- `plugins/StayLoaded/config.yml` (redact anything private)
- `plugins/StayLoaded/sessions.json`
- The relevant log excerpt

Do not include world files or player data in public issues.
