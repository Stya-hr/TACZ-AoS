package tacz.longrangesniping.ballistics;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tacz.longrangesniping.compat.tacz.TaczBridge.ShotContext;
import tacz.longrangesniping.config.ServerConfig;

import java.util.IdentityHashMap;
import java.util.Map;

public final class PrototypeBallistics {
    public static final PrototypeBallistics INSTANCE = new PrototypeBallistics();
    private final Map<ServerLevel, LocalBallistics> levels = new IdentityHashMap<>();
    private PrototypeBallistics() {}

    public LocalBallistics.Admission admit(EntityKineticBullet bullet, ShotContext context) {
        var settings = ServerConfig.current();
        if (!settings.available() || !settings.policy().enabled() || !settings.prototype().enabled()) {
            return LocalBallistics.Admission.DISABLED;
        }
        if (!(bullet.level() instanceof ServerLevel level)) return LocalBallistics.Admission.UNSUPPORTED;
        return levels.computeIfAbsent(level, LocalBallistics::new).admit(bullet, context, settings.prototype());
    }

    @SubscribeEvent public void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.tickRateManager().runsNormally()) {
            var scheduler = levels.get(level);
            if (scheduler != null) scheduler.tick();
        }
    }

    @SubscribeEvent public void unload(LevelEvent.Unload event) {
        var scheduler = levels.remove(event.getLevel());
        if (scheduler != null) scheduler.clear();
    }

    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        levels.values().forEach(LocalBallistics::clear);
        levels.clear();
    }

    public LocalBallistics.Status status(ServerLevel level) {
        var scheduler = levels.get(level);
        return scheduler == null ? new LocalBallistics.Status(0, 0, 0, 0, 0) : scheduler.status();
    }
}
