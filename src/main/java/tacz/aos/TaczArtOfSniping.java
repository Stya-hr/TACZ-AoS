package tacz.aos;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.common.MinecraftForge;
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

    public TaczArtOfSniping() {
        IEventBus modEventBus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        tacz.aos.block.ModContent.register(modEventBus);
        tacz.aos.trails.FeedbackSounds.register(modEventBus);
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC, tacz.aos.config.ConfigFileBootstrap.FILE_NAME);
        modEventBus.addListener(ServerConfig::onLoad);
        modEventBus.addListener(ServerConfig::onReload);
        modEventBus.addListener(ServerConfig::onUnload);
        modEventBus.addListener(this::commonSetup);
        ScopeNetworking.register();
        MinecraftForge.EVENT_BUS.register(new TaczEvents());
        MinecraftForge.EVENT_BUS.register(ScopedBallistics.INSTANCE);
        MinecraftForge.EVENT_BUS.register(tacz.aos.trails.DelayedGunshots.INSTANCE);
        MinecraftForge.EVENT_BUS.register(GeneratedChunks.INSTANCE);
        MinecraftForge.EVENT_BUS.register(tacz.aos.scoping.ScopeActivity.INSTANCE);
        MinecraftForge.EVENT_BUS.register(ScopeSubscriptions.INSTANCE);
        MinecraftForge.EVENT_BUS.register(tacz.aos.scoping.ScopeViews.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new SnipingCommand());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (net.minecraftforge.fml.ModList.get().isLoaded("curios")) tacz.aos.compat.curios.CuriosBeltCompat.register();
            var directory = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
            var defaults = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                    .resolve(net.minecraftforge.fml.loading.FMLConfig.defaultConfigPath());
            try {
                tacz.aos.config.ConfigFileBootstrap.ensureExists(directory, defaults);
            } catch (java.io.IOException exception) {
                LOGGER.error("Could not create config file {}", directory.resolve(tacz.aos.config.ConfigFileBootstrap.FILE_NAME), exception);
            }
        });
        LOGGER.info("TACZ: Art of Sniping initialized");
    }
}
