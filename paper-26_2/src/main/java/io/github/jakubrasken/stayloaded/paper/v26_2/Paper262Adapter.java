package io.github.jakubrasken.stayloaded.paper.v26_2;

import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import org.bukkit.Server;

public final class Paper262Adapter implements PlatformAdapter {
    private static final String MINECRAFT_VERSION = "26.2";

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
