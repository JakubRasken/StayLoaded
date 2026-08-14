package io.github.jakubrasken.stayloaded.paper;

import io.github.jakubrasken.stayloaded.paper.command.DebugProbeCommand;
import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import java.util.ServiceLoader;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class StayLoadedPlugin extends JavaPlugin {
    private PlatformAdapter platformAdapter;

    @Override
    public void onEnable() {
        platformAdapter = ServiceLoader.load(PlatformAdapter.class, getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No StayLoaded platform adapter was packaged"));

        platformAdapter.verifyServer(getServer());
        saveDefaultConfig();
        Objects.requireNonNull(getCommand("stayloaded-debug"), "stayloaded-debug command")
                .setExecutor(new DebugProbeCommand(this, platformAdapter));
        getLogger().info("StayLoaded initialized for Minecraft " + platformAdapter.minecraftVersion());
        getLogger().info("Player handoff is not enabled yet.");
        if (getConfig().getBoolean("debug-probes-enabled")) {
            getLogger().warning("Synthetic debug probes are enabled. Use only on a disposable test server.");
        }
    }

    @Override
    public void onDisable() {
        if (platformAdapter != null) {
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
