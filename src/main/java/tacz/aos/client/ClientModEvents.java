package tacz.aos.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import java.io.IOException;

/** Forge lifecycle events use the mod bus, separate from gameplay/render events. */
@Mod.EventBusSubscriber(modid = "tacz_aos", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        ScopedBlockRendering.setup(event);
        ScopedRendering.setup(event);
        ScopeRangeHud.setup(event);
        ShotTrails.setup(event);
    }
    @SubscribeEvent public static void shaders(RegisterShadersEvent event) throws IOException {
        ShotTrails.shaders(event);
    }
}
