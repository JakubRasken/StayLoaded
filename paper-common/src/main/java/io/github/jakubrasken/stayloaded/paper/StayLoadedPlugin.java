package io.github.jakubrasken.stayloaded.paper;

import io.github.jakubrasken.stayloaded.paper.command.DebugProbeCommand;
import io.github.jakubrasken.stayloaded.paper.command.StayLoadedCommand;
import io.github.jakubrasken.stayloaded.paper.handoff.StayLoadedCoordinator;
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
        Objects.requireNonNull(getCommand("stayloaded-debug"), "stayloaded-debug command")
                .setExecutor(new DebugProbeCommand(this, platformAdapter));
        Objects.requireNonNull(getCommand("stayloaded"), "stayloaded command")
                .setExecutor(new StayLoadedCommand(
                        coordinator,
                        getConfig().getLong("experimental-handoff.default-duration-seconds"),
                        getConfig().getLong("experimental-handoff.max-duration-seconds")
                ));
        getLogger().info("StayLoaded initialized for Minecraft " + platformAdapter.minecraftVersion());
        getLogger().info("Player handoff is not enabled yet.");
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
