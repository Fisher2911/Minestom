package net.minestom.server;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minestom.server.advancements.AdvancementManager;
import net.minestom.server.adventure.bossbar.BossBarManager;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.command.CommandManager;
import net.minestom.server.component.DataComponents;
import net.minestom.server.dialog.Dialog;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.entity.metadata.animal.ChickenVariant;
import net.minestom.server.entity.metadata.animal.CowVariant;
import net.minestom.server.entity.metadata.animal.FrogVariant;
import net.minestom.server.entity.metadata.animal.PigVariant;
import net.minestom.server.entity.metadata.animal.tameable.CatVariant;
import net.minestom.server.entity.metadata.animal.tameable.WolfSoundVariant;
import net.minestom.server.entity.metadata.animal.tameable.WolfVariant;
import net.minestom.server.entity.metadata.other.PaintingVariant;
import net.minestom.server.event.EventDispatcher;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.exception.ExceptionManager;
import net.minestom.server.instance.Chunk;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.block.BlockManager;
import net.minestom.server.instance.block.banner.BannerPattern;
import net.minestom.server.instance.block.jukebox.JukeboxSong;
import net.minestom.server.item.armor.TrimMaterial;
import net.minestom.server.item.armor.TrimPattern;
import net.minestom.server.item.enchant.*;
import net.minestom.server.item.instrument.Instrument;
import net.minestom.server.listener.manager.PacketListenerManager;
import net.minestom.server.message.ChatType;
import net.minestom.server.monitoring.BenchmarkManager;
import net.minestom.server.monitoring.EventsJFR;
import net.minestom.server.monitoring.TickMonitor;
import net.minestom.server.network.ConnectionManager;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.socket.Server;
import net.minestom.server.recipe.RecipeManager;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.scoreboard.TeamManager;
import net.minestom.server.snapshot.*;
import net.minestom.server.thread.Acquirable;
import net.minestom.server.thread.ThreadDispatcher;
import net.minestom.server.thread.ThreadProvider;
import net.minestom.server.timer.SchedulerManager;
import net.minestom.server.utils.PacketViewableUtils;
import net.minestom.server.utils.collection.MappedCollection;
import net.minestom.server.utils.time.Tick;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ServerProcessImpl implements ServerProcess {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerProcessImpl.class);

    private final ExceptionManager exception;

    private final DynamicRegistry<StructCodec<? extends LevelBasedValue>> enchantmentLevelBasedValues;
    private final DynamicRegistry<StructCodec<? extends ValueEffect>> enchantmentValueEffects;
    private final DynamicRegistry<StructCodec<? extends EntityEffect>> enchantmentEntityEffects;
    private final DynamicRegistry<StructCodec<? extends LocationEffect>> enchantmentLocationEffects;

    private final DynamicRegistry<ChatType> chatType;
    private final DynamicRegistry<Dialog> dialog;
    private final DynamicRegistry<DimensionType> dimensionType;
    private final DynamicRegistry<Biome> biome;
    private final DynamicRegistry<DamageType> damageType;
    private final DynamicRegistry<TrimMaterial> trimMaterial;
    private final DynamicRegistry<TrimPattern> trimPattern;
    private final DynamicRegistry<BannerPattern> bannerPattern;
    private final DynamicRegistry<Enchantment> enchantment;
    private final DynamicRegistry<PaintingVariant> paintingVariant;
    private final DynamicRegistry<JukeboxSong> jukeboxSong;
    private final DynamicRegistry<Instrument> instrument;
    private final DynamicRegistry<WolfVariant> wolfVariant;
    private final DynamicRegistry<WolfSoundVariant> wolfSoundVariant;
    private final DynamicRegistry<CatVariant> catVariant;
    private final DynamicRegistry<ChickenVariant> chickenVariant;
    private final DynamicRegistry<CowVariant> cowVariant;
    private final DynamicRegistry<FrogVariant> frogVariant;
    private final DynamicRegistry<PigVariant> pigVariant;

    private final ConnectionManager connection;
    private final PacketListenerManager packetListener;
    private final PacketParser<ClientPacket> packetParser;
    private final InstanceManager instance;
    private final BlockManager block;
    private final CommandManager command;
    private final RecipeManager recipe;
    private final TeamManager team;
    private final GlobalEventHandler eventHandler;
    private final SchedulerManager scheduler;
    private final BenchmarkManager benchmark;
    private final AdvancementManager advancement;
    private final BossBarManager bossBar;

    private final Server server;

    private final ThreadDispatcher<Chunk, Entity> dispatcher;
    private final Ticker ticker;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public ServerProcessImpl() {
        this.exception = new ExceptionManager();

        // The order of initialization here is relevant, we must load the enchantment util registries before the vanilla data is loaded.
        var ignoredForInit = DataComponents.ITEM_NAME;

        this.enchantmentLevelBasedValues = LevelBasedValue.createDefaultRegistry();
        this.enchantmentValueEffects = ValueEffect.createDefaultRegistry();
        this.enchantmentEntityEffects = EntityEffect.createDefaultRegistry();
        this.enchantmentLocationEffects = LocationEffect.createDefaultRegistry();

        this.chatType = ChatType.createDefaultRegistry();
        this.dialog = Dialog.createDefaultRegistry(this);
        this.dimensionType = DimensionType.createDefaultRegistry();
        this.biome = Biome.createDefaultRegistry();
        this.damageType = DamageType.createDefaultRegistry();
        this.trimMaterial = TrimMaterial.createDefaultRegistry();
        this.trimPattern = TrimPattern.createDefaultRegistry();
        this.bannerPattern = BannerPattern.createDefaultRegistry();
        this.enchantment = Enchantment.createDefaultRegistry(this);
        this.paintingVariant = PaintingVariant.createDefaultRegistry();
        this.jukeboxSong = JukeboxSong.createDefaultRegistry();
        this.instrument = Instrument.createDefaultRegistry();
        this.wolfVariant = WolfVariant.createDefaultRegistry();
        this.wolfSoundVariant = WolfSoundVariant.createDefaultRegistry();
        this.catVariant = CatVariant.createDefaultRegistry();
        this.chickenVariant = ChickenVariant.createDefaultRegistry();
        this.cowVariant = CowVariant.createDefaultRegistry();
        this.frogVariant = FrogVariant.createDefaultRegistry();
        this.pigVariant = PigVariant.createDefaultRegistry();

        this.connection = new ConnectionManager();
        this.packetListener = new PacketListenerManager();
        this.packetParser = PacketVanilla.CLIENT_PACKET_PARSER;
        this.instance = new InstanceManager(this);
        this.block = new BlockManager();
        this.command = new CommandManager();
        this.recipe = new RecipeManager();
        this.team = new TeamManager();
        this.eventHandler = new GlobalEventHandler();
        this.scheduler = new SchedulerManager();
        this.benchmark = new BenchmarkManager();
        this.advancement = new AdvancementManager();
        this.bossBar = new BossBarManager();

        this.server = new Server(packetParser);

        this.dispatcher = ThreadDispatcher.dispatcher(ThreadProvider.counter(), ServerFlag.DISPATCHER_THREADS);
        this.ticker = new TickerImpl();
    }

    @Override
    public @NotNull ExceptionManager exception() {
        return exception;
    }

    @Override
    public @NotNull DynamicRegistry<Dialog> dialog() {
        return dialog;
    }

    @Override
    public @NotNull DynamicRegistry<DamageType> damageType() {
        return damageType;
    }

    @Override
    public @NotNull DynamicRegistry<TrimMaterial> trimMaterial() {
        return trimMaterial;
    }

    @Override
    public @NotNull DynamicRegistry<TrimPattern> trimPattern() {
        return trimPattern;
    }

    @Override
    public @NotNull DynamicRegistry<BannerPattern> bannerPattern() {
        return bannerPattern;
    }

    @Override
    public @NotNull DynamicRegistry<Enchantment> enchantment() {
        return enchantment;
    }

    @Override
    public @NotNull DynamicRegistry<PaintingVariant> paintingVariant() {
        return paintingVariant;
    }

    @Override
    public @NotNull DynamicRegistry<JukeboxSong> jukeboxSong() {
        return jukeboxSong;
    }

    @Override
    public @NotNull DynamicRegistry<Instrument> instrument() {
        return instrument;
    }

    @Override
    public @NotNull DynamicRegistry<WolfVariant> wolfVariant() {
        return wolfVariant;
    }

    @Override
    public @NotNull DynamicRegistry<WolfSoundVariant> wolfSoundVariant() {
        return wolfSoundVariant;
    }

    @Override
    public @NotNull DynamicRegistry<CatVariant> catVariant() {
        return catVariant;
    }

    @Override
    public @NotNull DynamicRegistry<ChickenVariant> chickenVariant() {
        return chickenVariant;
    }

    @Override
    public @NotNull DynamicRegistry<CowVariant> cowVariant() {
        return cowVariant;
    }

    @Override
    public @NotNull DynamicRegistry<FrogVariant> frogVariant() {
        return frogVariant;
    }

    @Override
    public @NotNull DynamicRegistry<PigVariant> pigVariant() {
        return pigVariant;
    }

    @Override
    public @NotNull DynamicRegistry<StructCodec<? extends LevelBasedValue>> enchantmentLevelBasedValues() {
        return enchantmentLevelBasedValues;
    }

    @Override
    public @NotNull DynamicRegistry<StructCodec<? extends ValueEffect>> enchantmentValueEffects() {
        return enchantmentValueEffects;
    }

    @Override
    public @NotNull DynamicRegistry<StructCodec<? extends EntityEffect>> enchantmentEntityEffects() {
        return enchantmentEntityEffects;
    }

    @Override
    public @NotNull DynamicRegistry<StructCodec<? extends LocationEffect>> enchantmentLocationEffects() {
        return enchantmentLocationEffects;
    }

    @Override
    public @NotNull ConnectionManager connection() {
        return connection;
    }

    @Override
    public @NotNull InstanceManager instance() {
        return instance;
    }

    @Override
    public @NotNull BlockManager block() {
        return block;
    }

    @Override
    public @NotNull CommandManager command() {
        return command;
    }

    @Override
    public @NotNull RecipeManager recipe() {
        return recipe;
    }

    @Override
    public @NotNull TeamManager team() {
        return team;
    }

    @Override
    public @NotNull GlobalEventHandler eventHandler() {
        return eventHandler;
    }

    @Override
    public @NotNull SchedulerManager scheduler() {
        return scheduler;
    }

    @Override
    public @NotNull BenchmarkManager benchmark() {
        return benchmark;
    }

    @Override
    public @NotNull AdvancementManager advancement() {
        return advancement;
    }

    @Override
    public @NotNull BossBarManager bossBar() {
        return bossBar;
    }

    @Override
    public @NotNull DynamicRegistry<ChatType> chatType() {
        return chatType;
    }

    @Override
    public @NotNull DynamicRegistry<DimensionType> dimensionType() {
        return dimensionType;
    }

    @Override
    public @NotNull DynamicRegistry<Biome> biome() {
        return biome;
    }

    @Override
    public @NotNull PacketListenerManager packetListener() {
        return packetListener;
    }

    @Override
    public @NotNull PacketParser<ClientPacket> packetParser() {
        return packetParser;
    }

    @Override
    public @NotNull Server server() {
        return server;
    }

    @Override
    public @NotNull ThreadDispatcher<Chunk, Entity> dispatcher() {
        return dispatcher;
    }

    @Override
    public @NotNull Ticker ticker() {
        return ticker;
    }

    @Override
    public void start(@NotNull SocketAddress socketAddress) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Server already started");
        }

        LOGGER.info("Starting {} ({}) server.", MinecraftServer.getBrandName(), Git.version());

        // Init server
        try {
            server.init(socketAddress);
        } catch (IOException e) {
            exception.handleException(e);
            throw new RuntimeException(e);
        }

        // Start server
        server.start();

        LOGGER.info(MinecraftServer.getBrandName() + " server started successfully.");

        // Stop the server on SIGINT
        if (ServerFlag.SHUTDOWN_ON_SIGNAL) Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true))
            return;
        LOGGER.info("Stopping " + MinecraftServer.getBrandName() + " server.");
        scheduler.shutdown();
        connection.shutdown();
        server.stop();
        LOGGER.info("Shutting down all thread pools.");
        benchmark.disable();
        dispatcher.shutdown();
        LOGGER.info(MinecraftServer.getBrandName() + " server stopped successfully.");
    }

    @Override
    public boolean isAlive() {
        return started.get() && !stopped.get();
    }

    @Override
    public @NotNull ServerSnapshot updateSnapshot(@NotNull SnapshotUpdater updater) {
        List<AtomicReference<InstanceSnapshot>> instanceRefs = new ArrayList<>();
        Int2ObjectOpenHashMap<AtomicReference<EntitySnapshot>> entityRefs = new Int2ObjectOpenHashMap<>();
        for (Instance instance : instance.getInstances()) {
            instanceRefs.add(updater.reference(instance));
            for (Entity entity : instance.getEntities()) {
                entityRefs.put(entity.getEntityId(), updater.reference(entity));
            }
        }
        return new SnapshotImpl.Server(MappedCollection.plainReferences(instanceRefs), entityRefs);
    }

    private final class TickerImpl implements Ticker {
        @Override
        public void tick(long nanoTime) {
            EventsJFR.ServerTick serverTickEvent = new EventsJFR.ServerTick();
            serverTickEvent.begin();
            scheduler().processTick();

            // Connection tick (let waiting clients in, send keep alives, handle configuration players packets)
            connection().tick(nanoTime);

            // Server tick (chunks/entities)
            serverTick(nanoTime);

            scheduler().processTickEnd();

            // Flush all waiting packets
            PacketViewableUtils.flush();

            // Monitoring
            {
                final double acquisitionTimeMs = Acquirable.resetAcquiringTime() / 1e6D;
                final double tickTimeMs = (System.nanoTime() - nanoTime) / 1e6D;
                final TickMonitor tickMonitor = new TickMonitor(tickTimeMs, acquisitionTimeMs);
                EventDispatcher.call(new ServerTickMonitorEvent(tickMonitor));
            }
            serverTickEvent.commit();
        }

        private void serverTick(long nanoStart) {
            long milliStart = TimeUnit.NANOSECONDS.toMillis(nanoStart);
            // Tick all instances
            for (Instance instance : instance().getInstances()) {
                try {
                    instance.tick(milliStart);
                } catch (Exception e) {
                    exception().handleException(e);
                }
            }
            // Tick all chunks (and entities inside)
            dispatcher().updateAndAwait(nanoStart);

            // Clear removed entities & update threads
            final long tickDuration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanoStart);
            final long remainingTickDuration = Tick.SERVER_TICKS.getDuration().toNanos() - tickDuration;
            // the nanoTimeout for refreshThreads is the remaining tick duration
            dispatcher().refreshThreads(remainingTickDuration);
        }
    }
}
