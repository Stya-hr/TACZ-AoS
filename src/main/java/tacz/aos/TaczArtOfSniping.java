package tacz.aos;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import tacz.aos.command.SnipingCommand;
import tacz.aos.ballistics.ScopedBallistics;
import tacz.aos.compat.tacz.TaczEvents;
import tacz.aos.config.ServerConfig;
import tacz.aos.terrain.GeneratedChunks;
import tacz.aos.scoping.ScopeSubscriptions;
import tacz.aos.network.ScopeNetworking;

/** Entry point for the TACZ: Art of Sniping extension. */
@Mod(TaczArtOfSniping.MOD_ID)
public final class TaczArtOfSniping {
    public static final String MOD_ID = "tacz_aos";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TaczArtOfSniping(IEventBus modEventBus, ModContainer container) {
        tacz.aos.block.ModContent.register(modEventBus);
        tacz.aos.trails.FeedbackSounds.register(modEventBus);
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC, tacz.aos.config.ConfigFileBootstrap.FILE_NAME);
        modEventBus.addListener(ServerConfig::onLoad);
        modEventBus.addListener(ServerConfig::onReload);
        modEventBus.addListener(ServerConfig::onUnload);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(ScopeNetworking::register);
        NeoForge.EVENT_BUS.register(new TaczEvents());
        NeoForge.EVENT_BUS.register(ScopedBallistics.INSTANCE);
        NeoForge.EVENT_BUS.register(tacz.aos.trails.DelayedGunshots.INSTANCE);
        NeoForge.EVENT_BUS.register(GeneratedChunks.INSTANCE);
        NeoForge.EVENT_BUS.register(tacz.aos.scoping.ScopeActivity.INSTANCE);
        NeoForge.EVENT_BUS.register(ScopeSubscriptions.INSTANCE);
        NeoForge.EVENT_BUS.register(tacz.aos.scoping.ScopeViews.INSTANCE);
        NeoForge.EVENT_BUS.register(new SnipingCommand());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            var directory = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
            var defaults = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve(net.neoforged.fml.loading.FMLConfig.defaultConfigPath());
            try {
                tacz.aos.config.ConfigFileBootstrap.ensureExists(directory, defaults);
            } catch (java.io.IOException exception) {
                LOGGER.error("Could not create config file {}", directory.resolve(tacz.aos.config.ConfigFileBootstrap.FILE_NAME), exception);
            }
        });
        LOGGER.info("TACZ: Art of Sniping initialized");
    }
}
