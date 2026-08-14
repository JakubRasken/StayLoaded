package io.github.jakubrasken.stayloaded.paper;

import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import java.util.ServiceLoader;
import org.bukkit.plugin.java.JavaPlugin;

public final class StayLoadedPlugin extends JavaPlugin {
    private PlatformAdapter platformAdapter;

    @Override
    public void onEnable() {
        platformAdapter = ServiceLoader.load(PlatformAdapter.class, getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No StayLoaded platform adapter was packaged"));

        platformAdapter.verifyServer(getServer());
        getLogger().info("StayLoaded initialized for Minecraft " + platformAdapter.minecraftVersion());
        getLogger().info("Milestone 1 contains architecture only; player handoff is not enabled yet.");
    }

    @Override
    public void onDisable() {
        if (platformAdapter != null) {
            platformAdapter.close();
        }
    }
}
