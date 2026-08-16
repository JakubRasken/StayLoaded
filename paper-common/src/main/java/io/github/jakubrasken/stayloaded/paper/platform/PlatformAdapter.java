package io.github.jakubrasken.stayloaded.paper.platform;

import java.io.IOException;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;

public interface PlatformAdapter extends AutoCloseable {
    String minecraftVersion();

    void verifyServer(Server server);

    UUID spawnProbe(Location location);

    boolean removeProbe(UUID probeId);

    int probeCount();

    int removeAllProbes();

    PlayerSnapshot snapshotPlayer(Player player);

    void spawnStandIn(PlayerSnapshot snapshot) throws IOException;

    void saveAndRemoveStandIn(UUID playerId) throws IOException;

    void checkpointStandIn(UUID playerId) throws IOException;

    void restoreStandIn(UUID playerId, String playerName) throws IOException;

    boolean hasStandIn(UUID playerId);

    @Override
    default void close() {
    }
}
