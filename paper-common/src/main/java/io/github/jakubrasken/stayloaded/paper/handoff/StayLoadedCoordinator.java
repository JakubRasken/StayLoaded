package io.github.jakubrasken.stayloaded.paper.handoff;

import io.github.jakubrasken.stayloaded.core.persistence.JsonSessionStore;
import io.github.jakubrasken.stayloaded.core.persistence.SessionLoadResult;
import io.github.jakubrasken.stayloaded.core.session.SessionMachine;
import io.github.jakubrasken.stayloaded.core.session.SessionOutcome;
import io.github.jakubrasken.stayloaded.core.session.SessionStatus;
import io.github.jakubrasken.stayloaded.core.session.StayLoadedSession;
import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import io.github.jakubrasken.stayloaded.paper.platform.PlayerSnapshot;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class StayLoadedCoordinator implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PlatformAdapter platformAdapter;
    private final JsonSessionStore sessionStore;
    private final Map<UUID, StayLoadedSession> sessions = new HashMap<>();
    private final Set<UUID> armedPlayers = new HashSet<>();
    private final Map<UUID, PlayerSnapshot> pendingSnapshots = new HashMap<>();
    private boolean acceptingSessions = true;

    public StayLoadedCoordinator(JavaPlugin plugin, PlatformAdapter platformAdapter) throws IOException {
        this.plugin = plugin;
        this.platformAdapter = platformAdapter;
        this.sessionStore = new JsonSessionStore(plugin.getDataFolder().toPath().resolve("sessions.json"));
        loadAndReconcile();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireSessions, 20L, 20L);
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

        Bukkit.getScheduler().runTaskLater(plugin, () -> failCancelledKick(player.getUniqueId()), 10L);
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

    private void expireSessions() {
        Instant now = Instant.now();
        for (StayLoadedSession session : Set.copyOf(sessions.values())) {
            if (session.status() != SessionStatus.ACTIVE || !session.remainingAt(now).isZero()) {
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
            } catch (IOException | RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to expire stand-in " + session.playerId(), exception);
            }
        }
    }

    private void loadAndReconcile() throws IOException {
        SessionLoadResult loaded = sessionStore.load();
        Instant now = Instant.now();
        for (StayLoadedSession session : loaded.sessions()) {
            StayLoadedSession reconciled = session;
            if (!session.isTerminal()) {
                if (session.status() == SessionStatus.ACTIVE && session.remainingAt(now).isZero()) {
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
        for (StayLoadedSession session : Set.copyOf(sessions.values())) {
            if (session.status() != SessionStatus.ACTIVE || !platformAdapter.hasStandIn(session.playerId())) {
                continue;
            }
            try {
                platformAdapter.saveAndRemoveStandIn(session.playerId());
                persist(SessionMachine.terminate(session, SessionOutcome.PLUGIN_DISABLED, Instant.now()));
            } catch (IOException | RuntimeException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to close stand-in " + session.playerId(), exception);
            }
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("StayLoaded coordinator must run on the server thread");
        }
    }
}
