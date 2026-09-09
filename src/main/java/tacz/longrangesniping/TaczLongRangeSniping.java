package tacz.longrangesniping;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/** Entry point for the TACZ Long Range Sniping extension. */
@Mod(TaczLongRangeSniping.MOD_ID)
public final class TaczLongRangeSniping {
    public static final String MOD_ID = "tacz_long_range_sniping";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TaczLongRangeSniping(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("TACZ Long Range Sniping initialized");
    }
}
