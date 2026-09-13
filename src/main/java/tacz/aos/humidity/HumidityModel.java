package tacz.aos.humidity;

import java.util.SplittableRandom;

/** Stateless day/phase/slot field. Density coefficient folds fixed reference air conditions into one constant. */
public final class HumidityModel {
    private HumidityModel() {}
    private record Slot(long start, int length, double value) {}
    public static double sample(long seed, long time, int weather, HumiditySettings settings) {
        var slot = slot(seed, time, weather, settings);
        int duration = Math.min(settings.transitionTicks(), slot.length());
        long age = time - slot.start();
        if (duration == 0 || age >= duration) return slot.value();
        double previous = slot(seed, slot.start() - 1, weather, settings).value();
        return blend(previous, slot.value(), (double) age / duration);
    }
    private static Slot slot(long seed, long time, int weather, HumiditySettings settings) {
        long day = Math.floorDiv(time, 24000);
        int tod = (int) Math.floorMod(time, 24000);
        int index = 0;
        while (tod >= settings.phases().get(index).end()) index++;
        var phase = settings.phases().get(index);
        int number = (tod - phase.start()) / settings.periodTicks();
        int start = phase.start() + number * settings.periodTicks();
        var profile = settings.weather(weather);
        double mean = Math.clamp(phase.mean() + profile.saturationBias() * (100 - phase.mean()), settings.minimum(), settings.maximum());
        double amplitude = Math.min(profile.amplitude(), Math.min(mean - settings.minimum(), settings.maximum() - mean));
        var random = new SplittableRandom(mix(seed ^ settings.seedSalt() ^ 0x5dbcf724068e31a9L)
                ^ mix(day) ^ mix(index + 139L) ^ mix(number + 7919L));
        double noise = settings.distribution() == HumiditySettings.Distribution.TRIANGULAR
                ? random.nextDouble() - random.nextDouble() : 2 * random.nextDouble() - 1;
        return new Slot(day * 24000 + start, Math.min(settings.periodTicks(), phase.end() - start), mean + amplitude * noise);
    }
    public static double blend(double from, double to, double progress) {
        double t = Math.clamp(progress, 0, 1);
        return from + (to - from) * t * t * (3 - 2 * t);
    }
    public static double densityRatio(double rh, HumiditySettings settings) {
        return settings.enabled() ? (1 - settings.densityCoefficient() * rh / 100)
                / (1 - settings.densityCoefficient() * settings.reference() / 100) : 1;
    }
    public static float friction(float base, double densityRatio) {
        if (base <= 0 || base >= 1 || !Float.isFinite(base) || densityRatio == 1) return base;
        return (float) -Math.expm1(Math.log1p(-base) * densityRatio);
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
