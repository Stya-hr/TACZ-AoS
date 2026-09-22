package tacz.aos.ballistics;

import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import tacz.aos.compat.tacz.TaczBridge.ShotContext;
import tacz.aos.config.ServerConfig;

import java.util.IdentityHashMap;
import java.util.Map;

public final class ScopedBallistics {
    public static final ScopedBallistics INSTANCE = new ScopedBallistics();
    private final Map<ServerLevel, LocalBallistics> levels = new IdentityHashMap<>();
    private final Map<ServerLevel, LocalBallistics.Admission> lastAdmission = new IdentityHashMap<>();
    private ScopedBallistics() {}

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

    @SubscribeEvent public void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (event.level instanceof ServerLevel level) {
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
        return scheduler == null ? new LocalBallistics.Status(0, 0, 0, 0) : scheduler.status();
    }
    public String lastAdmission(ServerLevel level) {
        var result = lastAdmission.get(level);
        return result == null ? "none" : result.name();
    }
}
