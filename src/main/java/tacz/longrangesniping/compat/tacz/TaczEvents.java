package tacz.longrangesniping.compat.tacz;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.ballistics.LocalBallistics;
import tacz.longrangesniping.ballistics.PrototypeBallistics;
import tacz.longrangesniping.config.ServerConfig;
import tacz.longrangesniping.eligibility.Eligibility.Decision;

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
            NeoForge.EVENT_BUS.post(new TaczShotObservedEvent(bullet, context));
            var admission = PrototypeBallistics.INSTANCE.admit(bullet, context);
            if (admission == LocalBallistics.Admission.ACCEPTED) event.setCanceled(true);
            if (ServerConfig.current().diagnostics()) {
                TaczLongRangeSniping.LOGGER.info("Eligible TACZ shot: ballistics={}, {}", admission, context);
            }
        });
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ServerConfig.current().diagnostics()) {
            lastDiagnostic.clear();
            return;
        }
        Decision next = TaczBridge.evaluate(player);
        if (!next.equals(lastDiagnostic.put(player.getUUID(), next))) {
            TaczLongRangeSniping.LOGGER.info("Sniping eligibility {}: {}", player.getGameProfile().getName(), next);
        }
    }

    @SubscribeEvent public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) { lastDiagnostic.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) { lastDiagnostic.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void onRespawn(PlayerEvent.PlayerRespawnEvent event) { lastDiagnostic.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void onServerStopped(ServerStoppedEvent event) { lastDiagnostic.clear(); }
}
