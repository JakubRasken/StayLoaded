package io.github.jakubrasken.stayloaded.paper.v1_21_11;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

public final class Paper12111Adapter implements PlatformAdapter {
    private static final String MINECRAFT_VERSION = "1.21.11";
    private final Map<UUID, Probe> probes = new HashMap<>();
    private DedicatedServer server;

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
        this.server = ((CraftServer) server).getServer();
    }

    @Override
    public UUID spawnProbe(Location location) {
        requirePrimaryThread();
        if (server == null) {
            throw new IllegalStateException("Adapter has not been initialized");
        }
        if (!(location.getWorld() instanceof CraftWorld craftWorld)) {
            throw new IllegalArgumentException("Probe location must have a loaded CraftWorld");
        }

        ServerLevel level = craftWorld.getHandle();
        UUID probeId = newProbeId();
        String probeName = "SLProbe" + probeId.toString().replace("-", "").substring(0, 8);
        GameProfile profile = new GameProfile(probeId, probeName);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        SyntheticConnection connection = new SyntheticConnection();
        EmbeddedChannel channel = new EmbeddedChannel(false, false);
        ServerPlayer player = null;

        try {
            Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
            connection.configurePacketHandler(channel.pipeline());
            channel.register();
            connection.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

            var decorator = RegistryFriendlyByteBuf.decorator(server.registryAccess());
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(decorator));

            player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
            ServerGamePacketListenerImpl listener = new SyntheticGamePacketListener(
                    server,
                    connection,
                    player,
                    cookie
            );
            connection.setupInboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(decorator, listener), listener);
            player.snapTo(
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
            );
            level.addNewPlayer(player);
            probes.put(probeId, new Probe(player, level, connection, channel));
            return probeId;
        } catch (Exception exception) {
            try {
                cleanupPartial(player, level, connection, channel);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to initialize the synthetic probe channel", exception);
        }
    }

    @Override
    public boolean removeProbe(UUID probeId) {
        requirePrimaryThread();
        Probe probe = probes.get(probeId);
        if (probe == null) {
            return false;
        }
        cleanup(probe);
        probes.remove(probeId, probe);
        return true;
    }

    @Override
    public int removeAllProbes() {
        requirePrimaryThread();
        int removed = 0;
        RuntimeException failure = null;
        for (Map.Entry<UUID, Probe> entry : Map.copyOf(probes).entrySet()) {
            try {
                cleanup(entry.getValue());
                probes.remove(entry.getKey(), entry.getValue());
                removed++;
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return removed;
    }

    @Override
    public int probeCount() {
        requirePrimaryThread();
        return probes.size();
    }

    private UUID newProbeId() {
        UUID probeId;
        do {
            probeId = UUID.randomUUID();
        } while (probes.containsKey(probeId) || server.getPlayerList().getPlayer(probeId) != null);
        return probeId;
    }

    private void cleanup(Probe probe) {
        cleanupPartial(probe.player(), probe.level(), probe.connection(), probe.channel());
    }

    private void cleanupPartial(
            ServerPlayer player,
            ServerLevel level,
            SyntheticConnection connection,
            EmbeddedChannel channel
    ) {
        RuntimeException failure = null;
        if (player != null) {
            failure = attempt(failure, player::disconnect);
            failure = attempt(failure, () -> {
                if (!player.isRemoved()) {
                    level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                }
            });
            failure = attempt(failure, player::retireScheduler);
            failure = attempt(failure, () -> player.getAdvancements().stopListening());
            failure = attempt(failure, () -> player.getTextFilter().leave());
        }
        failure = attempt(failure, connection::clearPacketQueue);
        failure = attempt(failure, channel::finishAndReleaseAll);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException attempt(RuntimeException failure, Runnable action) {
        try {
            action.run();
            return failure;
        } catch (RuntimeException exception) {
            return appendFailure(failure, exception);
        }
    }

    private static RuntimeException appendFailure(RuntimeException failure, RuntimeException addition) {
        if (failure == null) {
            return addition;
        }
        failure.addSuppressed(addition);
        return failure;
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Synthetic probes must be managed on the server thread");
        }
    }

    private record Probe(
            ServerPlayer player,
            ServerLevel level,
            SyntheticConnection connection,
            EmbeddedChannel channel
    ) {
    }

    private static final class SyntheticConnection extends Connection {
        private SyntheticConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override
        public boolean isMemoryConnection() {
            return true;
        }

        @Override
        public void send(Packet<?> packet) {
            discard(packet, null);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) {
            discard(packet, listener);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            discard(packet, listener);
        }

        @Override
        public void flushChannel() {
        }

        @Override
        public void handleDisconnection() {
            clearPacketQueue();
        }

        private void discard(Packet<?> packet, ChannelFutureListener listener) {
            ServerPlayer player = getPlayer();
            ChannelFuture future = channel == null ? null : channel.newSucceededFuture();
            if (player != null) {
                packet.onPacketDispatch(player);
            }
            try {
                if (listener != null && future != null) {
                    listener.operationComplete(future);
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Synthetic packet completion failed", exception);
            } finally {
                if (player != null && future != null) {
                    packet.onPacketDispatchFinish(player, future);
                }
            }
        }
    }

    private static final class SyntheticGamePacketListener extends ServerGamePacketListenerImpl {
        private SyntheticGamePacketListener(
                DedicatedServer server,
                Connection connection,
                ServerPlayer player,
                CommonListenerCookie cookie
        ) {
            super(server, connection, player, cookie);
        }

        @Override
        public void tickClientLoadTimeout() {
        }
    }
}
