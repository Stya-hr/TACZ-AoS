package tacz.aos.wind;

import java.util.SplittableRandom;

/** Uniform bearing, truncated Rayleigh speed. No coordinates/player ID: a whole dimension shares each interval. */
public final class WindModel {
    private WindModel() {}
    public static WindVector sample(long worldSeed, long dayTime, int weather, WindSettings settings) {
        if (!settings.enabled()) return WindVector.ZERO;
        weather = settings.weatherEffects() ? weather : 0;
        var profile = settings.profile(weather);
        if (profile.meanSpeed() == 0 || profile.maxSpeed() == 0) return WindVector.ZERO;
        long day = Math.floorDiv(dayTime, 24000);
        long slot = Math.floorMod(dayTime, 24000) / profile.periodTicks();
        long seed = mix(worldSeed ^ settings.seedSalt()) ^ mix(day) ^ mix(slot + 0x632be59bd9b4e019L) ^ mix(weather + 193L);
        var random = new SplittableRandom(seed);
        double angle = random.nextDouble() * Math.PI * 2;
        // meanSpeed is the untruncated Rayleigh mean; truncation makes the actual mean slightly lower.
        double sigma = profile.meanSpeed() / Math.sqrt(Math.PI / 2);
        double ratio = profile.maxSpeed() / sigma;
        double cdfMax = -Math.expm1(-.5 * ratio * ratio);
        double speed = sigma * Math.sqrt(-2 * Math.log1p(-random.nextDouble() * cdfMax));
        speed = Math.min(speed, profile.maxSpeed());
        return new WindVector(Math.cos(angle) * speed, Math.sin(angle) * speed);
    }
    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
