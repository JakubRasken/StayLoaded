package io.github.jakubrasken.stayloaded.paper.command;

import io.github.jakubrasken.stayloaded.paper.handoff.StayLoadedCoordinator;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class StayLoadedCommand implements CommandExecutor {
    private final StayLoadedCoordinator coordinator;
    private final long defaultDurationSeconds;
    private final long maxDurationSeconds;

    public StayLoadedCommand(
            StayLoadedCoordinator coordinator,
            long defaultDurationSeconds,
            long maxDurationSeconds
    ) {
        this.coordinator = coordinator;
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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can start a StayLoaded session.");
            return true;
        }

        try {
            long seconds = arguments.length == 0 ? defaultDurationSeconds : parseDuration(arguments[0]);
            if (seconds <= 0 || seconds > maxDurationSeconds) {
                sender.sendMessage("Duration must be between 1 second and " + maxDurationSeconds + " seconds.");
                return true;
            }
            int noteStart = arguments.length == 0 ? 0 : 1;
            String note = String.join(" ", Arrays.copyOfRange(arguments, noteStart, arguments.length));
            coordinator.start(player, Duration.ofSeconds(seconds), note);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            sender.sendMessage("Use a duration such as 30s, 10m, or 1h.");
        } catch (IOException | IllegalStateException exception) {
            sender.sendMessage("StayLoaded could not start safely: " + exception.getMessage());
        }
        return true;
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
