package tacz.aos.humidity;

import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import tacz.aos.config.ServerConfig;

/** Server thread only. One sample per dimension/tick, no position, biome, chunk or disk queries. */
public final class HumidityField {
    public record Sample(boolean enabled, double percent, double densityRatio) {}
    private static final Sample DISABLED = new Sample(false, 0, 1);
    private static final WeakHashMap<ServerLevel, State> STATES = new WeakHashMap<>();
    private static final class State {
        HumiditySettings settings;
        long tick = Long.MIN_VALUE, time, weatherStart;
        int weather;
        double weatherFrom;
        Sample sample;
    }
    private HumidityField() {}
    public static Sample current(ServerLevel level) {
        var settings = ServerConfig.humidity();
        if (!settings.enabled() || !level.dimensionType().hasSkyLight()) { STATES.remove(level); return DISABLED; }
        int weather = !settings.weatherEffects() ? 0 : level.isThundering() ? 2 : level.isRaining() ? 1 : 0;
        long tick = level.getGameTime(), time = level.getDayTime();
        var state = STATES.computeIfAbsent(level, ignored -> new State());
        if (settings.equals(state.settings) && state.tick == tick && state.time == time && state.weather == weather) return state.sample;
        long seed = level.getSeed() ^ ((long) level.dimension().location().toString().hashCode() << 32);
        double target = HumidityModel.sample(seed, time, weather, settings);
        if (!settings.equals(state.settings) || tick < state.tick) {
            state.weatherStart = tick - settings.transitionTicks(); state.weatherFrom = target;
        } else if (state.weather != weather) {
            state.weatherStart = tick; state.weatherFrom = state.sample.percent();
        }
        double rh = settings.transitionTicks() == 0 ? target : HumidityModel.blend(state.weatherFrom, target,
                (double) (tick - state.weatherStart) / settings.transitionTicks());
        state.settings = settings; state.tick = tick; state.time = time; state.weather = weather;
        return state.sample = new Sample(true, rh, HumidityModel.densityRatio(rh, settings));
    }
    public static void clear() { STATES.clear(); }
}
