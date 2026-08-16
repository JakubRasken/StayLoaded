package io.github.jakubrasken.stayloaded.paper.message;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

/**
 * Configurable MiniMessage strings for operator output.
 *
 * <p>Keys live under {@code messages.} in config.yml. A missing or invalid
 * key falls back to the built-in default so the plugin never sends raw
 * legacy text or throws on a bad operator edit.
 */
public final class Messages {
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("session-started", "<green>Your StayLoaded stand-in is starting."),
            Map.entry("session-started-broadcast", "<yellow><player> left a StayLoaded stand-in (<duration>)."),
            Map.entry("session-ended-broadcast", "<yellow><player>'s StayLoaded stand-in ended (<reason>)."),
            Map.entry("help-header", "<yellow>StayLoaded - log off, stay loaded."),
            Map.entry("help-what", "<gray>Disconnect safely: a server-side stand-in keeps your farms and simulation running while you are away, then hands everything back when you return."),
            Map.entry("help-usage", "<green>/stayloaded [duration] [reason]"),
            Map.entry("help-usage-detail", "<gray>  Duration: <white>30s</white>, <white>10m</white>, or <white>1h</white> (default <white><default></white>). Optional reason is saved with the session."),
            Map.entry("help-example", "<gray>  Example: <white>/stayloaded 2h iron farm</white> - leave a stand-in for 2 hours."),
            Map.entry("help-commands", "<gray>Commands: <white>help</white>, <white>start [duration]</white>, <white>stop [player]</white>, <white>stopall</white>, <white>status [player]</white>, <white>list</white>, <white>reload</white>, <white>metrics</white>"),
            Map.entry("help-return", "<gray>Rejoin any time to reclaim your session; it ends automatically when the timer runs out."),
            Map.entry("help-warning", "<red>Experimental: not for valuable worlds yet."),
            Map.entry("status-header", "<yellow>Your StayLoaded session:"),
            Map.entry("status-remaining", "<gray>Remaining: <white><remaining></white>"),
            Map.entry("status-expires", "<gray>Expires: <white><expires></white>"),
            Map.entry("status-note", "<gray>Note: <white><note></white>"),
            Map.entry("status-none", "<gray>You have no active StayLoaded session. Use <white>/sl 30m</white> to start one."),
            Map.entry("stop-usage", "<gray>Usage: /sl stop [player]"),
            Map.entry("duration-invalid", "<red>Duration must be between 1 second and <max> seconds."),
            Map.entry("duration-format", "<red>Use a duration such as 30s, 10m, or 1h."),
            Map.entry("start-failed", "<red>StayLoaded could not start safely: <error>"),
            Map.entry("terminate-usage", "<red>Usage: /stayloaded terminate <player>"),
            Map.entry("terminate-permission", "<red>You do not have permission to terminate StayLoaded sessions."),
            Map.entry("terminate-unknown-player", "<red>No player named '<player>' is online."),
            Map.entry("terminate-done", "<green>Terminated the StayLoaded session for <player>."),
            Map.entry("stopall-done", "<green>Terminated <count> StayLoaded session(s)."),
            Map.entry("terminate-failed", "<red>StayLoaded could not terminate safely: <error>"),
            Map.entry("list-empty", "<gray>No active StayLoaded sessions."),
            Map.entry("list-header", "<yellow>StayLoaded sessions:"),
            Map.entry("list-entry", "<gray>- <white><player></gray> (<yellow><status></yellow>, <white><remaining></white> remaining)"),
            Map.entry("info-header", "<yellow>StayLoaded session for <player>:"),
            Map.entry("info-status", "<gray>Status: <white><status></gray>"),
            Map.entry("info-started", "<gray>Started: <white><started></gray>"),
            Map.entry("info-expires", "<gray>Expires: <white><expires></gray>"),
            Map.entry("info-note", "<gray>Note: <white><note></gray>"),
            Map.entry("info-respawns", "<gray>Respawns: <white><respawns></gray>"),
            Map.entry("info-none", "<gray>No StayLoaded session exists for <player>."),
            Map.entry("reload-done", "<green>StayLoaded configuration reloaded."),
            Map.entry("reload-failed", "<red>StayLoaded could not reload: <error>"),
            Map.entry("metrics-header", "<yellow>StayLoaded metrics:"),
            Map.entry("metrics-entry", "<gray>- <white><name></gray>: <yellow><count></yellow>"),
            Map.entry("metrics-empty", "<gray>No StayLoaded metrics recorded yet."),
            Map.entry("metrics-opt-out", "<gray>Metrics are disabled in config.yml."),
            Map.entry("metrics-failed", "<red>StayLoaded metrics could not be read: <error>"),
            Map.entry("metrics-location", "<gray>Metrics file: <white><path></gray>")
    );

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final ConfigView config;

    public Messages(ConfigView config) {
        this.config = config;
    }

    public Component send(CommandSender sender, String key, Map<String, String> placeholders) {
        Component message = render(key, placeholders);
        sender.sendMessage(message);
        return message;
    }

    public Component render(String key, Map<String, String> placeholders) {
        String template = config.message(key).orElseGet(() -> DEFAULTS.getOrDefault(key, "<gray>" + key));
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            template = template.replace("<" + placeholder.getKey() + ">", placeholder.getValue());
        }
        return miniMessage.deserialize(template);
    }

    public Component render(String key) {
        return render(key, Map.of());
    }

    /** Read-only view over the resolved config so this helper stays testable. */
    public interface ConfigView {
        Optional<String> message(String key);
    }

    public static String formatDuration(Duration duration) {
        long totalSeconds = duration.getSeconds();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        if (minutes > 0) {
            return minutes + "m" + (seconds > 0 ? " " + seconds + "s" : "");
        }
        return seconds + "s";
    }

    public static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }
}
