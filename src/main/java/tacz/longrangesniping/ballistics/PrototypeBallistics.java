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
    private final Map<ServerLevel, LocalBallistics.Admission> lastAdmission = new IdentityHashMap<>();
    private PrototypeBallistics() {}

    public LocalBallistics.Admission admit(EntityKineticBullet bullet, ShotContext context) {
        var settings = ServerConfig.current();
        if (!(bullet.level() instanceof ServerLevel level)) return LocalBallistics.Admission.UNSUPPORTED;
        if (!settings.available() || !settings.policy().enabled() || !settings.ballistics().enabled()) {
            lastAdmission.put(level, LocalBallistics.Admission.DISABLED);
            return LocalBallistics.Admission.DISABLED;
        }
        var admission = levels.computeIfAbsent(level, LocalBallistics::new).admit(bullet, context, settings.ballistics());
        lastAdmission.put(level, admission);
        return admission;
    }

    @SubscribeEvent public void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.tickRateManager().runsNormally()) {
            var scheduler = levels.get(level);
            if (scheduler != null) scheduler.tick();
        }
    }

    @SubscribeEvent public void unload(LevelEvent.Unload event) {
        var scheduler = levels.remove(event.getLevel());
        lastAdmission.remove(event.getLevel());
        if (scheduler != null) scheduler.clear();
    }

    @SubscribeEvent public void stopped(ServerStoppedEvent event) {
        levels.values().forEach(LocalBallistics::clear);
        levels.clear();
        lastAdmission.clear();
    }

    public LocalBallistics.Status status(ServerLevel level) {
        var scheduler = levels.get(level);
        return scheduler == null ? new LocalBallistics.Status(0, 0, 0, 0, 0) : scheduler.status();
    }
    public String lastAdmission(ServerLevel level) {
        var result = lastAdmission.get(level);
        return result == null ? "none" : result.name();
    }
}
