package tacz.longrangesniping;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import tacz.longrangesniping.command.SnipingCommand;
import tacz.longrangesniping.compat.tacz.TaczEvents;
import tacz.longrangesniping.config.ServerConfig;

/** Entry point for the TACZ Long Range Sniping extension. */
@Mod(TaczLongRangeSniping.MOD_ID)
public final class TaczLongRangeSniping {
    public static final String MOD_ID = "tacz_long_range_sniping";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TaczLongRangeSniping(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        modEventBus.addListener(ServerConfig::onLoad);
        modEventBus.addListener(ServerConfig::onReload);
        modEventBus.addListener(ServerConfig::onUnload);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(new TaczEvents());
        NeoForge.EVENT_BUS.register(new SnipingCommand());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("TACZ Long Range Sniping initialized");
    }
}
