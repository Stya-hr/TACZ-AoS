package tacz.aos.wind;

import net.minecraft.server.level.ServerLevel;
import tacz.aos.config.ServerConfig;

import java.util.Map;
import java.util.WeakHashMap;

/** Server-thread cache: at most one random sample per time/weather/config transition, never chunk reads. */
public final class WindField {
    private record Cached(WindSettings settings, long day, long slot, int weather, WindVector vector) {}
    private static final Map<ServerLevel, Cached> CACHE = new WeakHashMap<>();
    private WindField() {}
    public static WindVector current(ServerLevel level) {
        var settings = ServerConfig.wind();
        if (!settings.enabled() || !level.dimensionType().hasSkyLight()) return WindVector.ZERO;
        int weather = !settings.weatherEffects() ? 0 : level.isThundering() ? 2 : level.isRaining() ? 1 : 0;
        long time = level.getDayTime(), day = Math.floorDiv(time, 24000);
        long slot = Math.floorMod(time, 24000) / settings.profile(weather).periodTicks();
        var cached = CACHE.get(level);
        if (cached != null && cached.settings.equals(settings) && cached.day == day && cached.slot == slot && cached.weather == weather)
            return cached.vector;
        var vector = WindModel.sample(level.getSeed(), time, weather, settings);
        CACHE.put(level, new Cached(settings, day, slot, weather, vector));
        return vector;
    }
}
