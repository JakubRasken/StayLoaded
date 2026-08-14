package io.github.jakubrasken.stayloaded.paper.platform;

import org.bukkit.Server;

public interface PlatformAdapter extends AutoCloseable {
    String minecraftVersion();

    void verifyServer(Server server);

    @Override
    default void close() {
    }
}
