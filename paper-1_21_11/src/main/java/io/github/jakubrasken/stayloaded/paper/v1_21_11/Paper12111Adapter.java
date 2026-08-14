package io.github.jakubrasken.stayloaded.paper.v1_21_11;

import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import org.bukkit.Server;

public final class Paper12111Adapter implements PlatformAdapter {
    private static final String MINECRAFT_VERSION = "1.21.11";

    @Override
    public String minecraftVersion() {
        return MINECRAFT_VERSION;
    }

    @Override
    public void verifyServer(Server server) {
        if (!server.getMinecraftVersion().equals(MINECRAFT_VERSION)) {
            throw new IllegalStateException(
                    "This StayLoaded jar requires Minecraft " + MINECRAFT_VERSION
                            + ", but the server is " + server.getMinecraftVersion()
            );
        }
    }
}
