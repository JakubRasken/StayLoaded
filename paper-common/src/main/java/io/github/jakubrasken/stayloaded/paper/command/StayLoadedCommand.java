package io.github.jakubrasken.stayloaded.paper.command;

import io.github.jakubrasken.stayloaded.core.session.SessionStatus;
import io.github.jakubrasken.stayloaded.core.session.StayLoadedSession;
import io.github.jakubrasken.stayloaded.paper.handoff.StayLoadedCoordinator;
import io.github.jakubrasken.stayloaded.paper.message.Messages;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class StayLoadedCommand implements CommandExecutor, TabCompleter {
    private final StayLoadedCoordinator coordinator;
    private final Messages messages;
    private final long defaultDurationSeconds;
    private final long maxDurationSeconds;

    public StayLoadedCommand(
            StayLoadedCoordinator coordinator,
            Messages messages,
            long defaultDurationSeconds,
            long maxDurationSeconds
    ) {
        this.coordinator = coordinator;
        this.messages = messages;
        this.defaultDurationSeconds = defaultDurationSeconds;
        this.maxDurationSeconds = maxDurationSeconds;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (arguments.length == 0) {
            return help(sender);
        }
        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                return help(sender);
            }
            case "start" -> {
                return start(sender, Arrays.copyOfRange(arguments, 1, arguments.length));
            }
            case "stop", "terminate" -> {
                return stop(sender, arguments);
            }
            case "stopall", "terminateall" -> {
                return stopAll(sender);
            }
            case "status", "info" -> {
                return status(sender, arguments);
            }
            case "list" -> {
                if (!sender.hasPermission("stayloaded.admin.list")) {
                    return noPermission(sender);
                }
                return list(sender);
            }
            case "reload" -> {
                if (!sender.hasPermission("stayloaded.admin.reload")) {
                    return noPermission(sender);
                }
                return reload(sender);
            }
            case "metrics" -> {
                if (!sender.hasPermission("stayloaded.admin.metrics")) {
                    return noPermission(sender);
                }
                return metrics(sender);
            }
            default -> {
                return start(sender, arguments);
            }
        }
    }

    private boolean help(CommandSender sender) {
        messages.send(sender, "help-header", Map.of());
        messages.send(sender, "help-what", Map.of());
        messages.send(sender, "help-usage", Map.of());
        messages.send(sender, "help-usage-detail", Map.of(
                "default", Messages.formatDuration(Duration.ofSeconds(defaultDurationSeconds))
        ));
        messages.send(sender, "help-example", Map.of());
        messages.send(sender, "help-commands", Map.of());
        messages.send(sender, "help-return", Map.of());
        messages.send(sender, "help-warning", Map.of());
        return true;
    }

    private boolean noPermission(CommandSender sender) {
        sender.sendMessage("You do not have permission to use this StayLoaded command.");
        return true;
    }

    private boolean start(CommandSender sender, String[] arguments) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can start a StayLoaded session.");
            return true;
        }

        try {
            long seconds = arguments.length == 0 ? defaultDurationSeconds : parseDuration(arguments[0]);
            if (seconds <= 0 || seconds > maxDurationSeconds) {
                messages.send(sender, "duration-invalid", Map.of("max", Long.toString(maxDurationSeconds)));
                return true;
            }
            int noteStart = arguments.length == 0 ? 0 : 1;
            String note = String.join(" ", Arrays.copyOfRange(arguments, noteStart, arguments.length));
            coordinator.start(player, Duration.ofSeconds(seconds), note);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            messages.send(sender, "duration-format", Map.of());
        } catch (IOException | IllegalStateException exception) {
            messages.send(sender, "start-failed", Map.of("error", String.valueOf(exception.getMessage())));
        }
        return true;
    }

    private boolean stop(CommandSender sender, String[] arguments) {
        String target;
        if (arguments.length >= 2) {
            target = arguments[1];
        } else if (sender instanceof Player player) {
            target = player.getName();
        } else {
            messages.send(sender, "stop-usage", Map.of());
            return true;
        }
        final String resolvedTarget = target;

        boolean selfStop = sender instanceof Player player && player.getName().equalsIgnoreCase(target);
        if (!selfStop && !sender.hasPermission("stayloaded.terminate")) {
            messages.send(sender, "terminate-permission", Map.of());
            return true;
        }

        UUID playerId;
        try {
            playerId = UUID.fromString(resolvedTarget);
        } catch (IllegalArgumentException exception) {
            playerId = coordinator.sessionsSnapshot().stream()
                    .filter(session -> !session.isTerminal() && session.playerName().equalsIgnoreCase(resolvedTarget))
                    .map(StayLoadedSession::playerId)
                    .findFirst()
                    .orElse(null);
            if (playerId == null) {
                Player player = Bukkit.getPlayerExact(resolvedTarget);
                if (player == null) {
                    messages.send(sender, "terminate-unknown-player", Map.of("player", resolvedTarget));
                    return true;
                }
                playerId = player.getUniqueId();
            }
        }

        try {
            coordinator.terminateForAdmin(playerId);
            messages.send(sender, "terminate-done", Map.of("player", resolvedTarget));
        } catch (IOException | IllegalStateException exception) {
            messages.send(sender, "terminate-failed", Map.of("error", String.valueOf(exception.getMessage())));
        }
        return true;
    }

    private boolean stopAll(CommandSender sender) {
        if (!sender.hasPermission("stayloaded.terminate")) {
            messages.send(sender, "terminate-permission", Map.of());
            return true;
        }
        try {
            int terminated = coordinator.terminateAllForAdmin();
            messages.send(sender, "stopall-done", Map.of("count", Integer.toString(terminated)));
        } catch (IOException exception) {
            messages.send(sender, "terminate-failed", Map.of("error", String.valueOf(exception.getMessage())));
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        List<StayLoadedSession> active = coordinator.sessionsSnapshot().stream()
                .filter(session -> !session.isTerminal())
                .sorted(Comparator.comparing(StayLoadedSession::playerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (active.isEmpty()) {
            messages.send(sender, "list-empty", Map.of());
            return true;
        }
        messages.send(sender, "list-header", Map.of());
        for (StayLoadedSession session : active) {
            messages.send(sender, "list-entry", Map.of(
                    "player", session.playerName(),
                    "status", Messages.capitalize(session.status().name()),
                    "remaining", Messages.formatDuration(session.remainingAt(java.time.Instant.now()))
            ));
        }
        return true;
    }

    private boolean status(CommandSender sender, String[] arguments) {
        String target;
        if (arguments.length >= 2) {
            target = arguments[1];
        } else if (sender instanceof Player player) {
            target = player.getName();
        } else {
            messages.send(sender, "terminate-usage", Map.of());
            return true;
        }
        final String resolvedTarget = target;

        boolean selfStatus = sender instanceof Player player && player.getName().equalsIgnoreCase(target);
        if (!selfStatus && !sender.hasPermission("stayloaded.admin.list")) {
            return noPermission(sender);
        }

        Optional<StayLoadedSession> session = coordinator.sessionsSnapshot().stream()
                .filter(candidate -> !candidate.isTerminal())
                .filter(candidate -> candidate.playerName().equalsIgnoreCase(resolvedTarget)
                        || candidate.playerId().toString().equalsIgnoreCase(resolvedTarget))
                .findFirst();
        if (session.isEmpty()) {
            if (selfStatus) {
                messages.send(sender, "status-none", Map.of());
            } else {
                messages.send(sender, "info-none", Map.of("player", resolvedTarget));
            }
            return true;
        }
        StayLoadedSession value = session.get();
        if (selfStatus) {
            messages.send(sender, "status-header", Map.of());
            messages.send(sender, "status-remaining", Map.of(
                    "remaining", Messages.formatDuration(value.remainingAt(java.time.Instant.now()))
            ));
            messages.send(sender, "status-expires", Map.of("expires", value.expiresAt().toString()));
            if (!value.note().isEmpty()) {
                messages.send(sender, "status-note", Map.of("note", value.note()));
            }
        } else {
            messages.send(sender, "info-header", Map.of("player", value.playerName()));
            messages.send(sender, "info-status", Map.of("status", Messages.capitalize(value.status().name())));
            messages.send(sender, "info-started", Map.of("started", value.startedAt().toString()));
            messages.send(sender, "info-expires", Map.of("expires", value.expiresAt().toString()));
            messages.send(sender, "info-note", Map.of("note", value.note().isEmpty() ? "-" : value.note()));
            messages.send(sender, "info-respawns", Map.of("respawns", Integer.toString(value.respawnCount())));
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        try {
            coordinator.reloadConfig();
            messages.send(sender, "reload-done", Map.of());
        } catch (RuntimeException exception) {
            messages.send(sender, "reload-failed", Map.of("error", String.valueOf(exception.getMessage())));
        }
        return true;
    }

    private boolean metrics(CommandSender sender) {
        if (!coordinator.metricsEnabled()) {
            messages.send(sender, "metrics-opt-out", Map.of());
            return true;
        }
        Map<String, Long> counts = coordinator.metrics().counts();
        if (counts.isEmpty()) {
            messages.send(sender, "metrics-empty", Map.of());
            return true;
        }
        messages.send(sender, "metrics-header", Map.of());
        counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> messages.send(sender, "metrics-entry", Map.of(
                        "name", friendlyMetricName(entry.getKey()),
                        "count", Long.toString(entry.getValue())
                )));
        return true;
    }

    private static String friendlyMetricName(String key) {
        return switch (key) {
            case "session_started" -> "Sessions started";
            case "player_returned" -> "Players returned";
            case "session_expired" -> "Sessions expired";
            case "session_died" -> "Stand-ins died";
            case "admin_terminated" -> "Admin terminations";
            default -> key.replace('_', ' ');
        };
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] arguments
    ) {
        List<String> suggestions = new ArrayList<>();
        if (arguments.length == 1) {
            suggestions.addAll(List.of("help", "start", "stop", "stopall", "status", "list", "reload", "metrics"));
        } else if (arguments.length == 2 && ("stop".equalsIgnoreCase(arguments[0])
                || "terminate".equalsIgnoreCase(arguments[0])
                || "status".equalsIgnoreCase(arguments[0])
                || "info".equalsIgnoreCase(arguments[0]))) {
            boolean forStop = "stop".equalsIgnoreCase(arguments[0]) || "terminate".equalsIgnoreCase(arguments[0]);
            if (!forStop || sender.hasPermission("stayloaded.terminate")) {
                for (StayLoadedSession session : coordinator.sessionsSnapshot()) {
                    if (!session.isTerminal()) {
                        suggestions.add(session.playerName());
                    }
                }
            }
        }
        String prefix = arguments[arguments.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private static long parseDuration(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.length() < 2) {
            throw new IllegalArgumentException("Missing duration unit");
        }
        long amount = Long.parseLong(normalized.substring(0, normalized.length() - 1));
        return switch (normalized.charAt(normalized.length() - 1)) {
            case 's' -> amount;
            case 'm' -> Math.multiplyExact(amount, 60);
            case 'h' -> Math.multiplyExact(amount, 3600);
            default -> throw new IllegalArgumentException("Unknown duration unit");
        };
    }
}
