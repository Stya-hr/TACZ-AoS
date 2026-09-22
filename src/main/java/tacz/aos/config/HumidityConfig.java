package tacz.aos.config;

import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;
import tacz.aos.humidity.HumiditySettings;

/** Server config owns all humidity inputs; no temperature, pressure or biome settings. */
final class HumidityConfig {
    private final ForgeConfigSpec.BooleanValue enabled, weather;
    private final ForgeConfigSpec.LongValue salt;
    private final ForgeConfigSpec.IntValue period, transition;
    private final ForgeConfigSpec.DoubleValue minimum, maximum, reference, coefficient;
    private final ForgeConfigSpec.EnumValue<HumiditySettings.Distribution> distribution;
    private final ForgeConfigSpec.ConfigValue<List<? extends String>> phases;
    private final ForgeConfigSpec.DoubleValue[] bias = new ForgeConfigSpec.DoubleValue[3], amplitude = new ForgeConfigSpec.DoubleValue[3];
    HumidityConfig(ForgeConfigSpec.Builder b) {
        b.comment("Global relative humidity (% RH), ordinary TACZ kinetic bullets only; fixed per shot, no biome queries.",
                "HUD follows rangefinder/ADS eligibility. Disabled hides its HUD row and leaves air friction unchanged.",
                "No effect in water or dimensions without skylight. Restart world/server after edits.").push("humidity");
        enabled = b.define("enabled", true);
        weather = b.define("weatherEffects", true);
        salt = b.defineInRange("seedSalt", 0L, Long.MIN_VALUE, Long.MAX_VALUE);
        period = b.comment("Random interval within each phase. The last interval may be shorter.").defineInRange("periodTicks", 4000, 20, 24000);
        transition = b.comment("Smoothstep transition at slot/phase and weather changes; zero gives immediate changes.")
                .defineInRange("transitionTicks", 200, 0, 24000);
        distribution = b.comment("TRIANGULAR favors the mean; UNIFORM gives equal probability across the interval.")
                .defineEnum("distribution", HumiditySettings.Distribution.TRIANGULAR);
        minimum = b.defineInRange("minimumRH", 5.0, 0, 100);
        maximum = b.defineInRange("maximumRH", 99.0, 0, 100);
        reference = b.comment("Base gun friction is calibrated at this RH. No correction at this value.").defineInRange("referenceRH", 50.0, 0, 100);
        coefficient = b.comment("Density factor k=(1-c*RH/100)/(1-c*referenceRH/100); corrected friction=1-(1-base)^k.",
                        "Default 0.0087264 models the small moisture-only effect in fixed reference air. Larger values amplify it.",
                        "No temperature/pressure simulation. Zero removes drag correction while retaining the humidity readout.")
                .defineInRange("densityCoefficient", .0087264, 0, .1);
        phases = b.comment("Ordered startTick|endTick|clearMeanRH, start inclusive/end exclusive; cover 0..24000 exactly.",
                        "0 is 06:00, 6000 noon, 12000 18:00, 18000 midnight. Means must be inside RH limits.")
                .defineListAllowEmpty("phases", HumiditySettings.DEFAULT_PHASES, value -> value instanceof String);
        String[] names = {"clear", "rain", "thunder"};
        for (int i = 0; i < 3; i++) {
            var p = HumiditySettings.DEFAULT.weather(i); b.push(names[i]);
            bias[i] = b.comment("Mean += bias*(100-mean), then bounded to RH limits.").defineInRange("saturationBias", p.saturationBias(), 0, 1);
            amplitude[i] = b.comment("Plus/minus percentage points; reduced symmetrically near RH limits to preserve the bounded mean.")
                    .defineInRange("amplitude", p.amplitude(), 0, 100);
            b.pop();
        }
        b.pop();
    }
    HumiditySettings read() {
        return new HumiditySettings(enabled.get(), weather.get(), salt.get(), period.get(), transition.get(),
                minimum.get(), maximum.get(), reference.get(), coefficient.get(), distribution.get(),
                HumiditySettings.parsePhases(phases.get()), profile(0), profile(1), profile(2));
    }
    private HumiditySettings.Weather profile(int i) { return new HumiditySettings.Weather(bias[i].get(), amplitude[i].get()); }
}
