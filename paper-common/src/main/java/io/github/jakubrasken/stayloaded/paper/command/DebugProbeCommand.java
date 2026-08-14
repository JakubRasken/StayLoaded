package io.github.jakubrasken.stayloaded.paper.command;

import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class DebugProbeCommand implements CommandExecutor {
    private final JavaPlugin plugin;
    private final PlatformAdapter platformAdapter;

    public DebugProbeCommand(JavaPlugin plugin, PlatformAdapter platformAdapter) {
        this.plugin = plugin;
        this.platformAdapter = platformAdapter;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!plugin.getConfig().getBoolean("debug-probes-enabled")) {
            sender.sendMessage("StayLoaded debug probes are disabled in config.yml.");
            return true;
        }
        if (arguments.length < 2 || !arguments[0].equalsIgnoreCase("probe")) {
            return false;
        }

        return switch (arguments[1].toLowerCase(Locale.ROOT)) {
            case "spawn" -> spawn(sender, arguments);
            case "remove" -> remove(sender, arguments);
            case "count" -> count(sender);
            case "clear" -> clear(sender);
            default -> false;
        };
    }

    private boolean spawn(CommandSender sender, String[] arguments) {
        Location location;
        if (arguments.length == 2 && sender instanceof Player player) {
            location = player.getLocation();
        } else if (arguments.length == 6) {
            World world = Bukkit.getWorld(arguments[2]);
            if (world == null) {
                sender.sendMessage("Unknown world: " + arguments[2]);
                return true;
            }
            try {
                double x = Double.parseDouble(arguments[3]);
                double y = Double.parseDouble(arguments[4]);
                double z = Double.parseDouble(arguments[5]);
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                    sender.sendMessage("Coordinates must be finite numbers.");
                    return true;
                }
                location = new Location(
                        world,
                        x,
                        y,
                        z
                );
            } catch (NumberFormatException exception) {
                sender.sendMessage("Coordinates must be numbers.");
                return true;
            }
        } else {
            return false;
        }
        UUID probeId = platformAdapter.spawnProbe(location);
        sender.sendMessage("Spawned StayLoaded debug probe " + probeId + ".");
        return true;
    }

    private boolean remove(CommandSender sender, String[] arguments) {
        if (arguments.length != 3) {
            return false;
        }
        try {
            UUID probeId = UUID.fromString(arguments[2]);
            boolean removed = platformAdapter.removeProbe(probeId);
            sender.sendMessage(removed ? "Removed debug probe." : "No debug probe has that UUID.");
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("Invalid UUID: " + arguments[2]);
        }
        return true;
    }

    private boolean clear(CommandSender sender) {
        int removed = platformAdapter.removeAllProbes();
        sender.sendMessage("Removed " + removed + " debug probe(s).");
        return true;
    }

    private boolean count(CommandSender sender) {
        sender.sendMessage("StayLoaded debug probes: " + platformAdapter.probeCount());
        return true;
    }
}
