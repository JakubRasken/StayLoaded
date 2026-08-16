package io.github.jakubrasken.stayloaded.paper.handoff;

import io.github.jakubrasken.stayloaded.core.metrics.MetricsRecorder;
import io.github.jakubrasken.stayloaded.core.persistence.DurableFiles;
import io.github.jakubrasken.stayloaded.core.persistence.JsonSessionStore;
import io.github.jakubrasken.stayloaded.core.persistence.SessionLoadResult;
import io.github.jakubrasken.stayloaded.core.session.SessionMachine;
import io.github.jakubrasken.stayloaded.core.session.SessionOutcome;
import io.github.jakubrasken.stayloaded.core.session.SessionStatus;
import io.github.jakubrasken.stayloaded.core.session.StayLoadedSession;
import io.github.jakubrasken.stayloaded.paper.message.Messages;
import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import io.github.jakubrasken.stayloaded.paper.platform.PlayerSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;

public final class StayLoadedCoordinator implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PlatformAdapter platformAdapter;
    private final JsonSessionStore sessionStore;
    private final Path cleanShutdownMarker;
    private final MetricsRecorder metricsRecorder;
    private final Messages messages;
    private final Map<UUID, StayLoadedSession> sessions = new HashMap<>();
    private final Set<UUID> armedPlayers = new HashSet<>();
    private final Map<UUID, PlayerSnapshot> pendingSnapshots = new HashMap<>();
    private long checkpointIntervalTicks;
    private boolean restoreAfterRestart;
    private int maxStandInRespawns;
    private boolean broadcastSessionChanges;
    private long lastCheckpointTick;
    private boolean acceptingSessions = true;

    public StayLoadedCoordinator(JavaPlugin plugin, PlatformAdapter platformAdapter) throws IOException {
        this.plugin = plugin;
        this.platformAdapter = platformAdapter;
        this.sessionStore = new JsonSessionStore(plugin.getDataFolder().toPath().resolve("sessions.json"));
        this.cleanShutdownMarker = plugin.getDataFolder().toPath().resolve("shutdown-clean.marker");
        this.metricsRecorder = new MetricsRecorder(plugin.getDataFolder().toPath().resolve("metrics.json"));
        this.messages = new Messages(key -> {
            String value = plugin.getConfig().getString("messages." + key);
            return Optional.ofNullable(value);
        });
        reloadConfig();
        loadAndReconcile();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickSessions, 20L, 20L);
    }

    public void reloadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.restoreAfterRestart = config.getBoolean(
                "experimental-handoff.restore-after-restart",
                false
        );
        this.maxStandInRespawns = config.getInt(
                "experimental-handoff.max-stand-in-respawns",
                3
        );
        long checkpointIntervalSeconds = config.getLong(
                "experimental-handoff.checkpoint-interval-seconds",
                60L
        );
        this.checkpointIntervalTicks = checkpointIntervalSeconds > 0
                ? Math.max(20L, checkpointIntervalSeconds * 20L)
                : 0L;
        this.broadcastSessionChanges = config.getBoolean("broadcast-session-changes", false);
    }

    public Messages.ConfigView messageConfig() {
        return key -> {
            String value = plugin.getConfig().getString("messages." + key);
            return Optional.ofNullable(value);
        };
    }

    public List<StayLoadedSession> sessionsSnapshot() {
        requirePrimaryThread();
        return List.copyOf(sessions.values());
    }

    public Optional<StayLoadedSession> sessionFor(UUID playerId) {
        requirePrimaryThread();
        return Optional.ofNullable(sessions.get(playerId));
    }

    public MetricsRecorder metrics() {
        return metricsRecorder;
    }

    public boolean metricsEnabled() {
        return plugin.getConfig().getBoolean("metrics.enabled", true);
    }

    public void start(Player player, Duration duration, String note) throws IOException {
        requirePrimaryThread();
        if (!acceptingSessions) {
            throw new IllegalStateException("StayLoaded disabled new sessions after a storage failure");
        }
        if (!plugin.getConfig().getBoolean("experimental-handoff.enabled")) {
            throw new IllegalStateException("Experimental handoff is disabled in config.yml");
        }
        if (!Bukkit.getOnlineMode()
                && !plugin.getConfig().getBoolean("experimental-handoff.allow-insecure-offline-mode")) {
            throw new IllegalStateException("Offline-mode handoff requires explicit insecure-mode acknowledgement");
        }
        if (player.isDead() || player.isSleeping() || player.isInsideVehicle()) {
            throw new IllegalStateException("Leave death, sleep, or vehicles before starting a handoff");
        }
        StayLoadedSession existing = sessions.get(player.getUniqueId());
        if (existing != null && !existing.isTerminal()) {
            throw new IllegalStateException("This player already has a StayLoaded session");
        }

        Instant now = Instant.now();
        StayLoadedSession starting = SessionMachine.create(
                player.getUniqueId(),
                player.getName(),
                now,
                duration,
                note
        );
        persist(starting);
        armedPlayers.add(player.getUniqueId());
        player.kick(Component.text("Your StayLoaded stand-in is starting."));
        metricsRecorder.record("session_started");
        if (broadcastSessionChanges) {
            Bukkit.broadcast(messages.render("session-started-broadcast", Map.of(
                    "player", player.getName(),
                    "duration", Messages.formatDuration(duration)
            )));
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> failCancelledKick(player.getUniqueId()), 10L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onStandInDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        StayLoadedSession session = sessions.get(playerId);
        if (session == null || session.status() != SessionStatus.ACTIVE || !platformAdapter.hasStandIn(playerId)) {
            return;
        }

        event.setCancelled(true);
        event.setDroppedExp(0);
        event.getDrops().clear();

        int respawnCount = session.respawnCount();
        if (respawnCount < maxStandInRespawns) {
            try {
                platformAdapter.respawnStandIn(playerId);
                StayLoadedSession respawned = SessionMachine.respawn(session, Instant.now());
                try {
                    persist(respawned);
                } catch (IOException persistenceFailure) {
                    sessions.put(playerId, respawned);
                    acceptingSessions = false;
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Stand-in data is safe, but the respawn could not be persisted for " + playerId,
                            persistenceFailure
                    );
                }
                plugin.getLogger().warning(
                        "Stand-in for " + session.playerName() + " died; respawned it ("
                                + (respawnCount + 1) + "/" + maxStandInRespawns + ")"
                );
            } catch (IOException | RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Failed to respawn the stand-in for " + session.playerName(),
                        exception
                );
            }
            return;
        }

        try {
            platformAdapter.removeStandIn(playerId);
            persist(SessionMachine.terminate(session, SessionOutcome.PLAYER_DIED, Instant.now()));
            metricsRecorder.record("session_died");
            broadcastSessionEnd(session.playerName(), "died");
            plugin.getLogger().warning(
                    "Stand-in for " + session.playerName() + " died past the respawn cap; "
                            + "ended its session with the last checkpoint intact"
            );
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Failed to safely end the session after the stand-in for " + session.playerName() + " died",
                    exception
            );
        }
    }

    private void broadcastSessionEnd(String playerName, String reason) {
        if (broadcastSessionChanges) {
            Bukkit.broadcast(messages.render("session-ended-broadcast", Map.of(
                    "player", playerName,
                    "reason", reason
            )));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onStandInPortal(EntityPortalEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        if (platformAdapter.hasStandIn(playerId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onStandInTeleport(PlayerTeleportEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (platformAdapter.hasStandIn(playerId) && event.getCause() == TeleportCause.ENDER_PEARL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onStandInMount(EntityMountEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        if (platformAdapter.hasStandIn(playerId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!armedPlayers.remove(playerId)) {
            return;
        }

        try {
            PlayerSnapshot snapshot = platformAdapter.snapshotPlayer(event.getPlayer());
            pendingSnapshots.put(playerId, snapshot);
            Bukkit.getScheduler().runTask(plugin, () -> activateStandIn(playerId));
        } catch (RuntimeException exception) {
            failStartingSession(playerId, exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPreLogin(PlayerPreLoginEvent event) {
        if (event.getResult() != PlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID playerId = event.getUniqueId();
        StayLoadedSession session = sessions.get(playerId);
        if (session == null || session.isTerminal()) {
            return;
        }

        if (session.status() == SessionStatus.STARTING) {
            armedPlayers.remove(playerId);
            pendingSnapshots.remove(playerId);
            try {
                persist(SessionMachine.terminate(session, SessionOutcome.RECOVERY_FAILED, Instant.now()));
            } catch (IOException exception) {
                disallow(event, "StayLoaded could not cancel the pending handoff. Try again shortly.", exception);
            }
            return;
        }
        if (session.status() != SessionStatus.ACTIVE || !platformAdapter.hasStandIn(playerId)) {
            disallow(event, "StayLoaded is reconciling your session. Try again shortly.", null);
            return;
        }

        try {
            StayLoadedSession returning = SessionMachine.beginReturn(session, Instant.now());
            persist(returning);
            platformAdapter.saveAndRemoveStandIn(playerId);
            StayLoadedSession completed = SessionMachine.completeReturn(returning, Instant.now());
            try {
                persist(completed);
            } catch (IOException persistenceFailure) {
                sessions.put(playerId, completed);
                acceptingSessions = false;
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Player data is safe, but the completed session could not be persisted for " + playerId,
                        persistenceFailure
                );
            }
            metricsRecorder.record("player_returned");
            broadcastSessionEnd(session.playerName(), "returned");
        } catch (IOException | RuntimeException exception) {
            StayLoadedSession current = sessions.get(playerId);
            if (current != null && current.status() == SessionStatus.RETURNING && platformAdapter.hasStandIn(playerId)) {
                try {
                    persist(SessionMachine.abortReturn(current, Instant.now()));
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                    acceptingSessions = false;
                }
            }
            disallow(event, "StayLoaded could not safely save your stand-in. Try again shortly.", exception);
        }
    }

    public void terminateForAdmin(UUID playerId) throws IOException {
        requirePrimaryThread();
        StayLoadedSession session = sessions.get(playerId);
        if (session == null || session.isTerminal()) {
            throw new IllegalStateException("No active StayLoaded session exists for that player");
        }
        if (platformAdapter.hasStandIn(playerId)) {
            platformAdapter.saveAndRemoveStandIn(playerId);
        }
        StayLoadedSession terminated = SessionMachine.terminate(session, SessionOutcome.ADMIN_TERMINATED, Instant.now());
        try {
            persist(terminated);
        } catch (IOException persistenceFailure) {
            sessions.put(playerId, terminated);
            acceptingSessions = false;
            throw persistenceFailure;
        }
        metricsRecorder.record("admin_terminated");
        broadcastSessionEnd(session.playerName(), "terminated by an operator");
    }

    private void activateStandIn(UUID playerId) {
        PlayerSnapshot snapshot = pendingSnapshots.remove(playerId);
        StayLoadedSession session = sessions.get(playerId);
        if (snapshot == null || session == null || session.status() != SessionStatus.STARTING) {
            return;
        }
        if (Bukkit.getPlayer(playerId) != null) {
            failStartingSession(playerId, new IllegalStateException("Player remained online after disconnect"));
            return;
        }

        try {
            if (session.remainingAt(Instant.now()).isZero()) {
                persist(SessionMachine.terminate(session, SessionOutcome.RECOVERY_FAILED, Instant.now()));
                return;
            }
            platformAdapter.spawnStandIn(snapshot);
            try {
                persist(SessionMachine.activate(session, Instant.now()));
            } catch (IOException exception) {
                platformAdapter.saveAndRemoveStandIn(playerId);
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            failStartingSession(playerId, exception);
        }
    }

    private void failCancelledKick(UUID playerId) {
        if (!armedPlayers.remove(playerId)) {
            return;
        }
        failStartingSession(playerId, new IllegalStateException("Player disconnect was cancelled"));
    }

    private void failStartingSession(UUID playerId, Exception exception) {
        plugin.getLogger().log(Level.SEVERE, "Failed to start StayLoaded session for " + playerId, exception);
        StayLoadedSession session = sessions.get(playerId);
        if (session != null && session.status() == SessionStatus.STARTING) {
            try {
                persist(SessionMachine.terminate(session, SessionOutcome.RECOVERY_FAILED, Instant.now()));
            } catch (IOException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
                acceptingSessions = false;
            }
        }
    }

    private void tickSessions() {
        expireSessions();
        if (checkpointIntervalTicks == 0L) {
            return;
        }
        long currentTick = Bukkit.getCurrentTick();
        if (currentTick - lastCheckpointTick < checkpointIntervalTicks) {
            return;
        }
        lastCheckpointTick = currentTick;
        checkpointSessions();
    }

    private void checkpointSessions() {
        Instant now = Instant.now();
        for (StayLoadedSession session : Set.copyOf(sessions.values())) {
            if (session.status() != SessionStatus.ACTIVE || !platformAdapter.hasStandIn(session.playerId())) {
                continue;
            }
            if (!platformAdapter.isStandInAlive(session.playerId())) {
                endDeadStandIn(session, now);
                continue;
            }
            if (session.checkpointedAt() != null && !session.checkpointedAt().isBefore(now.minusSeconds(
                    checkpointIntervalTicks / 20L
            ))) {
                continue;
            }
            try {
                platformAdapter.checkpointStandIn(session.playerId());
                StayLoadedSession checkpointed = SessionMachine.checkpoint(session, now);
                try {
                    persist(checkpointed);
                } catch (IOException persistenceFailure) {
                    sessions.put(session.playerId(), checkpointed);
                    acceptingSessions = false;
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Stand-in data is safe, but the checkpoint could not be persisted for " + session.playerId(),
                            persistenceFailure
                    );
                }
            } catch (IOException | RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to checkpoint stand-in " + session.playerId(), exception);
            }
        }
    }

    private void expireSessions() {
        Instant now = Instant.now();
        for (StayLoadedSession session : Set.copyOf(sessions.values())) {
            if (session.status() != SessionStatus.ACTIVE || !session.remainingAt(now).isZero()) {
                continue;
            }
            if (!platformAdapter.hasStandIn(session.playerId())) {
                continue;
            }
            if (!platformAdapter.isStandInAlive(session.playerId())) {
                endDeadStandIn(session, now);
                continue;
            }
            try {
                platformAdapter.saveAndRemoveStandIn(session.playerId());
                StayLoadedSession expired = SessionMachine.expire(session, now);
                try {
                    persist(expired);
                } catch (IOException persistenceFailure) {
                    sessions.put(session.playerId(), expired);
                    acceptingSessions = false;
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Player data is safe, but expiry could not be persisted for " + session.playerId(),
                            persistenceFailure
                    );
                }
                metricsRecorder.record("session_expired");
                broadcastSessionEnd(session.playerName(), "expired");
            } catch (IOException | RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to expire stand-in " + session.playerId(), exception);
            }
        }
    }

    private void endDeadStandIn(StayLoadedSession session, Instant now) {
        try {
            platformAdapter.removeStandIn(session.playerId());
            persist(SessionMachine.terminate(session, SessionOutcome.PLAYER_DIED, now));
            metricsRecorder.record("session_died");
            broadcastSessionEnd(session.playerName(), "died");
            plugin.getLogger().warning(
                    "Stand-in for " + session.playerName() + " was found dead; "
                            + "ended its session with the last checkpoint intact"
            );
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Failed to safely end the session after the stand-in for " + session.playerName() + " died",
                    exception
            );
        }
    }

    private void loadAndReconcile() throws IOException {
        SessionLoadResult loaded = sessionStore.load();
        Instant now = Instant.now();
        boolean cleanShutdown = Files.notExists(cleanShutdownMarker);
        if (cleanShutdown) {
            Files.deleteIfExists(cleanShutdownMarker);
        }
        for (StayLoadedSession session : loaded.sessions()) {
            StayLoadedSession reconciled = session;
            if (!session.isTerminal()) {
                if (cleanShutdown) {
                    reconciled = SessionMachine.terminate(session, SessionOutcome.RECOVERY_FAILED, now);
                } else if (restoreAfterRestart
                        && session.status() == SessionStatus.ACTIVE
                        && session.checkpointedAt() != null
                        && !session.remainingAt(now).isZero()) {
                    try {
                        platformAdapter.restoreStandIn(session.playerId(), session.playerName());
                        reconciled = SessionMachine.activate(session, session.updatedAt());
                        plugin.getLogger().info("Restored StayLoaded stand-in for " + session.playerName());
                    } catch (IOException | RuntimeException exception) {
                        plugin.getLogger().log(
                                Level.SEVERE,
                                "Failed to restore stand-in for " + session.playerName(),
                                exception
                        );
                        reconciled = SessionMachine.terminate(session, SessionOutcome.RECOVERY_FAILED, now);
                    }
                } else if (session.status() == SessionStatus.ACTIVE && session.remainingAt(now).isZero()) {
                    reconciled = SessionMachine.expire(session, now);
                } else {
                    reconciled = SessionMachine.terminate(session, SessionOutcome.RECOVERY_FAILED, now);
                }
            }
            sessions.put(reconciled.playerId(), reconciled);
        }
        sessionStore.save(sessions.values());
        if (loaded.recovered()) {
            plugin.getLogger().warning("Recovered StayLoaded sessions from " + loaded.source());
        }
    }

    private void persist(StayLoadedSession session) throws IOException {
        Map<UUID, StayLoadedSession> next = new HashMap<>(sessions);
        next.put(session.playerId(), session);
        sessionStore.save(next.values());
        sessions.clear();
        sessions.putAll(next);
    }

    private void disallow(PlayerPreLoginEvent event, String message, Exception exception) {
        event.disallow(PlayerPreLoginEvent.Result.KICK_OTHER, message);
        if (exception != null) {
            plugin.getLogger().log(Level.SEVERE, "Pre-login stand-in cleanup failed for " + event.getUniqueId(), exception);
        }
    }

    @Override
    public void close() {
        requirePrimaryThread();
        acceptingSessions = false;
        Set<UUID> pendingPlayerIds = new HashSet<>(armedPlayers);
        pendingPlayerIds.addAll(pendingSnapshots.keySet());
        armedPlayers.clear();
        pendingSnapshots.clear();
        for (UUID playerId : pendingPlayerIds) {
            StayLoadedSession session = sessions.get(playerId);
            if (session != null && session.status() == SessionStatus.STARTING) {
                try {
                    persist(SessionMachine.terminate(session, SessionOutcome.PLUGIN_DISABLED, Instant.now()));
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to close pending session " + playerId, exception);
                }
            }
        }

        boolean allSaved = true;
        for (StayLoadedSession session : Set.copyOf(sessions.values())) {
            if (session.status() != SessionStatus.ACTIVE || !platformAdapter.hasStandIn(session.playerId())) {
                continue;
            }
            try {
                platformAdapter.checkpointStandIn(session.playerId());
                StayLoadedSession checkpointed = SessionMachine.checkpoint(session, Instant.now());
                try {
                    persist(checkpointed);
                } catch (IOException persistenceFailure) {
                    sessions.put(session.playerId(), checkpointed);
                    allSaved = false;
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Stand-in data is safe, but the final checkpoint could not be persisted for "
                                    + session.playerId(),
                            persistenceFailure
                    );
                }
            } catch (IOException | RuntimeException exception) {
                allSaved = false;
                plugin.getLogger().log(Level.SEVERE, "Failed to checkpoint stand-in " + session.playerId(), exception);
            }
        }

        if (allSaved) {
            try {
                DurableFiles.writeAtomically(cleanShutdownMarker, new byte[0]);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to record a clean shutdown", exception);
            }
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("StayLoaded coordinator must run on the server thread");
        }
    }
}
