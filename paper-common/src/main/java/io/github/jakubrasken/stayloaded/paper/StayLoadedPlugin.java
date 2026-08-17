package io.github.jakubrasken.stayloaded.paper;

import io.github.jakubrasken.stayloaded.paper.command.DebugProbeCommand;
import io.github.jakubrasken.stayloaded.paper.command.StayLoadedCommand;
import io.github.jakubrasken.stayloaded.paper.handoff.StayLoadedCoordinator;
import io.github.jakubrasken.stayloaded.paper.message.Messages;
import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import java.io.IOException;
import java.util.ServiceLoader;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class StayLoadedPlugin extends JavaPlugin {
    private PlatformAdapter platformAdapter;
    private StayLoadedCoordinator coordinator;

    @Override
    public void onEnable() {
        platformAdapter = ServiceLoader.load(PlatformAdapter.class, getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No StayLoaded platform adapter was packaged"));

        platformAdapter.verifyServer(getServer());
        saveDefaultConfig();
        try {
            coordinator = new StayLoadedCoordinator(this, platformAdapter);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize StayLoaded session storage", exception);
        }
        Messages messages = new Messages(key -> {
            String value = getConfig().getString("messages." + key);
            return java.util.Optional.ofNullable(value);
        });
        Objects.requireNonNull(getCommand("stayloaded-debug"), "stayloaded-debug command")
                .setExecutor(new DebugProbeCommand(this, platformAdapter));
        StayLoadedCommand stayLoadedCommand = new StayLoadedCommand(
                coordinator,
                messages,
                getConfig().getLong("experimental-handoff.default-duration-seconds"),
                getConfig().getLong("experimental-handoff.max-duration-seconds")
        );
        Objects.requireNonNull(getCommand("stayloaded"), "stayloaded command")
                .setExecutor(stayLoadedCommand);
        Objects.requireNonNull(getCommand("stayloaded"), "stayloaded command tab")
                .setTabCompleter(stayLoadedCommand);
        getLogger().info("StayLoaded initialized for Minecraft " + platformAdapter.minecraftVersion());
        if (getConfig().getBoolean("experimental-handoff.enabled")) {
            getLogger().warning("Player handoff is ENABLED. Use only on disposable test servers.");
        } else {
            getLogger().info("Player handoff is disabled (experimental-handoff.enabled: false).");
        }
        if (getConfig().getBoolean("debug-probes-enabled")) {
            getLogger().warning("Synthetic debug probes are enabled. Use only on a disposable test server.");
        }
    }

    @Override
    public void onDisable() {
        if (platformAdapter != null) {
            if (coordinator != null) {
                try {
                    coordinator.close();
                } catch (RuntimeException exception) {
                    getLogger().log(Level.SEVERE, "Failed to close the StayLoaded coordinator", exception);
                }
            }
            try {
                int removed = platformAdapter.removeAllProbes();
                if (removed > 0) {
                    getLogger().info("Removed " + removed + " synthetic debug probe(s).");
                }
            } catch (RuntimeException exception) {
                getLogger().log(Level.SEVERE, "Failed to clean up every synthetic debug probe", exception);
            } finally {
                platformAdapter.close();
            }
        }
    }
}
