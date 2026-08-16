package io.github.jakubrasken.stayloaded.paper.v1_21_11;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.github.jakubrasken.stayloaded.core.persistence.DurableFiles;
import io.github.jakubrasken.stayloaded.paper.platform.PlatformAdapter;
import io.github.jakubrasken.stayloaded.paper.platform.PlayerSnapshot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
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
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

public final class Paper12111Adapter implements PlatformAdapter {
    private static final String MINECRAFT_VERSION = "1.21.11";
    private final Map<UUID, Probe> probes = new HashMap<>();
    private final Map<UUID, SyntheticPlayer> standIns = new HashMap<>();
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
        SyntheticPlayer syntheticPlayer = createSyntheticPlayer(
                level,
                profile,
                ClientInformation.createDefault(),
                player -> player.snapTo(
                        location.getX(),
                        location.getY(),
                        location.getZ(),
                        location.getYaw(),
                        location.getPitch()
                )
        );
        probes.put(probeId, new Probe(
                syntheticPlayer.player(),
                syntheticPlayer.level(),
                syntheticPlayer.connection(),
                syntheticPlayer.channel()
        ));
        return probeId;
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

    @Override
    public PlayerSnapshot snapshotPlayer(Player player) {
        requirePrimaryThread();
        requireInitialized();
        if (!(player instanceof CraftPlayer craftPlayer)) {
            throw new IllegalArgumentException("Player must be a CraftPlayer");
        }

        ServerPlayer serverPlayer = craftPlayer.getHandle();
        return new Snapshot(
                serverPlayer.getGameProfile(),
                serverPlayer.clientInformation(),
                serverPlayer.level()
        );
    }

    @Override
    public void spawnStandIn(PlayerSnapshot snapshot) throws IOException {
        requirePrimaryThread();
        requireInitialized();
        if (!(snapshot instanceof Snapshot paperSnapshot)) {
            throw new IllegalArgumentException("Snapshot was not created by this adapter");
        }

        UUID playerId = paperSnapshot.playerId();
        if (server.getPlayerList().getPlayer(playerId) != null) {
            throw new IllegalStateException("A real player is still registered for " + playerId);
        }
        if (standIns.containsKey(playerId)) {
            throw new IllegalStateException("A stand-in is already registered for " + playerId);
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntities().get(playerId) != null) {
                throw new IllegalStateException("An entity already owns player UUID " + playerId);
            }
        }

        CompoundTag authoritativeData = readPlayerData(playerId);
        SyntheticPlayer standIn = createSyntheticPlayer(
                paperSnapshot.level(),
                paperSnapshot.profile(),
                paperSnapshot.clientInformation(),
                player -> player.load(TagValueInput.create(
                        ProblemReporter.DISCARDING,
                        server.registryAccess(),
                        authoritativeData
                ))
        );
        standIns.put(playerId, standIn);
    }

    @Override
    public void saveAndRemoveStandIn(UUID playerId) throws IOException {
        requirePrimaryThread();
        requireInitialized();
        SyntheticPlayer standIn = standIns.get(playerId);
        if (standIn == null) {
            throw new IllegalStateException("No stand-in exists for " + playerId);
        }
        if (server.getPlayerList().getPlayer(playerId) != null) {
            throw new IllegalStateException("Cannot save a stand-in while the real player is registered");
        }

        writeStandInData(standIn.player(), playerId);

        try {
            cleanup(standIn);
        } finally {
            if (standIn.player().isRemoved()) {
                standIns.remove(playerId, standIn);
            }
        }
    }

    @Override
    public void checkpointStandIn(UUID playerId) throws IOException {
        requirePrimaryThread();
        requireInitialized();
        SyntheticPlayer standIn = standIns.get(playerId);
        if (standIn == null) {
            throw new IllegalStateException("No stand-in exists for " + playerId);
        }
        if (server.getPlayerList().getPlayer(playerId) != null) {
            throw new IllegalStateException("Cannot checkpoint a stand-in while the real player is registered");
        }

        writeStandInData(standIn.player(), playerId);
    }

    private void writeStandInData(ServerPlayer standIn, UUID playerId) throws IOException {
        CompoundTag savedData = savePlayerData(standIn);
        ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
        NbtIo.writeCompressed(savedData, compressedOutput);
        byte[] compressedData = compressedOutput.toByteArray();

        Path playerDataDirectory = server.getPlayerList().playerIo.getPlayerDir().toPath();
        Path dataFile = playerDataDirectory.resolve(playerId + ".dat");
        Path oldDataFile = playerDataDirectory.resolve(playerId + ".dat_old");
        byte[] previousData = validatedPreviousData(dataFile);
        if (previousData != null) {
            DurableFiles.writeAtomically(oldDataFile, previousData);
        }
        try {
            DurableFiles.writeAtomically(dataFile, compressedData);
            CompoundTag checkedData = NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap());
            if (!savedData.equals(checkedData)) {
                throw new IOException("Player data read-back did not match for " + playerId);
            }
        } catch (IOException exception) {
            if (previousData != null) {
                DurableFiles.writeAtomically(dataFile, previousData);
            }
            throw exception;
        }
    }

    @Override
    public boolean hasStandIn(UUID playerId) {
        requirePrimaryThread();
        return standIns.containsKey(playerId);
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

    private void cleanup(SyntheticPlayer syntheticPlayer) {
        cleanupPartial(
                syntheticPlayer.player(),
                syntheticPlayer.level(),
                syntheticPlayer.connection(),
                syntheticPlayer.channel()
        );
    }

    private SyntheticPlayer createSyntheticPlayer(
            ServerLevel level,
            GameProfile profile,
            ClientInformation clientInformation,
            Consumer<ServerPlayer> initializer
    ) {
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

            player = new ServerPlayer(server, level, profile, clientInformation);
            ServerGamePacketListenerImpl listener = new SyntheticGamePacketListener(
                    server,
                    connection,
                    player,
                    cookie
            );
            connection.setupInboundProtocol(GameProtocols.SERVERBOUND_TEMPLATE.bind(decorator, listener), listener);
            initializer.accept(player);
            level.addNewPlayer(player);
            return new SyntheticPlayer(player, level, connection, channel);
        } catch (Exception exception) {
            try {
                cleanupPartial(player, level, connection, channel);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to initialize the synthetic player channel", exception);
        }
    }

    private CompoundTag savePlayerData(ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING,
                server.registryAccess()
        );
        player.saveWithoutId(output);
        return output.buildResult();
    }

    private CompoundTag readPlayerData(UUID playerId) throws IOException {
        Path dataFile = server.getPlayerList().playerIo.getPlayerDir().toPath().resolve(playerId + ".dat");
        if (Files.notExists(dataFile)) {
            throw new IOException("Paper did not save authoritative player data for " + playerId);
        }
        return NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap());
    }

    private byte[] validatedPreviousData(Path dataFile) throws IOException {
        if (Files.notExists(dataFile)) {
            return null;
        }
        byte[] data = Files.readAllBytes(dataFile);
        NbtIo.readCompressed(dataFile, NbtAccounter.unlimitedHeap());
        return data;
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
            throw new IllegalStateException("Synthetic players must be managed on the server thread");
        }
    }

    private void requireInitialized() {
        if (server == null) {
            throw new IllegalStateException("Adapter has not been initialized");
        }
    }

    private record Snapshot(
            GameProfile profile,
            ClientInformation clientInformation,
            ServerLevel level
    ) implements PlayerSnapshot {
        @Override
        public UUID playerId() {
            return profile.id();
        }

        @Override
        public String playerName() {
            return profile.name();
        }
    }

    private record Probe(
            ServerPlayer player,
            ServerLevel level,
            SyntheticConnection connection,
            EmbeddedChannel channel
    ) {
    }

    private record SyntheticPlayer(
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
