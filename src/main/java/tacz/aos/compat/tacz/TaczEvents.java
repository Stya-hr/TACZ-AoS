package tacz.aos.compat.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.ballistics.LocalBallistics;
import tacz.aos.ballistics.ScopedBallistics;
import tacz.aos.config.ServerConfig;
import tacz.aos.eligibility.Eligibility.Decision;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TaczEvents {
    private final Map<UUID, Decision> lastDiagnostic = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBulletSpawn(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || event.loadedFromDisk()
                || !(event.getEntity() instanceof EntityKineticBullet bullet)) return;
        TaczBridge.inspectNewBullet(bullet).ifPresent(context -> {
            MinecraftForge.EVENT_BUS.post(new TaczShotObservedEvent(bullet, context));
            var admission = ScopedBallistics.INSTANCE.admit(bullet, context);
            if (admission == LocalBallistics.Admission.ACCEPTED) event.setCanceled(true);
            if (ServerConfig.current().diagnostics()) {
                TaczArtOfSniping.LOGGER.info("Eligible TACZ shot: ballistics={}, {}", admission, context);
            }
        });
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!ServerConfig.current().diagnostics()) {
            lastDiagnostic.clear();
            return;
        }
        Decision next = TaczBridge.evaluate(player);
        if (!next.equals(lastDiagnostic.put(player.getUUID(), next))) {
            TaczArtOfSniping.LOGGER.info("Sniping eligibility {}: {}", player.getGameProfile().getName(), next);
        }
    }

    @SubscribeEvent public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) { lastDiagnostic.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) { lastDiagnostic.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void onRespawn(PlayerEvent.PlayerRespawnEvent event) { lastDiagnostic.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void onServerStopped(ServerStoppedEvent event) { lastDiagnostic.clear(); }
}
