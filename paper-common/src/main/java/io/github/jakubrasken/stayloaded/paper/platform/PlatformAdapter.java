package io.github.jakubrasken.stayloaded.paper.platform;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;

public interface PlatformAdapter extends AutoCloseable {
    String minecraftVersion();

    void verifyServer(Server server);

    UUID spawnProbe(Location location);

    boolean removeProbe(UUID probeId);

    int probeCount();

    int removeAllProbes();

    @Override
    default void close() {
    }
}
