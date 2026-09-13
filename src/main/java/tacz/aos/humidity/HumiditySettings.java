package tacz.aos.humidity;

import java.util.ArrayList;
import java.util.List;

/** Relative humidity in percent; offsets/amplitudes are percentage points, not fractions. */
public record HumiditySettings(boolean enabled, boolean weatherEffects, long seedSalt, int periodTicks,
                               int transitionTicks, double minimum, double maximum, double reference,
                               double densityCoefficient, Distribution distribution, List<Phase> phases,
                               Weather clear, Weather rain, Weather thunder) {
    public enum Distribution { TRIANGULAR, UNIFORM }
    public record Phase(int start, int end, double mean) {}
    public record Weather(double saturationBias, double amplitude) {
        public Weather {
            if (!Double.isFinite(saturationBias + amplitude) || saturationBias < 0 || saturationBias > 1
                    || amplitude < 0 || amplitude > 100) throw new IllegalArgumentException("Invalid humidity weather profile");
        }
    }
    public static final List<String> DEFAULT_PHASES = List.of("0|6000|65", "6000|10000|45",
            "10000|16000|60", "16000|20000|70", "20000|24000|80");
    public static final HumiditySettings DEFAULT = new HumiditySettings(true, true, 0, 4000, 200,
            5, 99, 50, .0087264, Distribution.TRIANGULAR, parsePhases(DEFAULT_PHASES),
            new Weather(0, 8), new Weather(.65, 5), new Weather(.8, 3));
    public static final HumiditySettings DISABLED = new HumiditySettings(false, true, 0, 4000, 200,
            5, 99, 50, .0087264, Distribution.TRIANGULAR, DEFAULT.phases, DEFAULT.clear, DEFAULT.rain, DEFAULT.thunder);
    public HumiditySettings {
        phases = List.copyOf(phases);
        if (periodTicks < 20 || periodTicks > 24000 || transitionTicks < 0 || transitionTicks > 24000
                || !Double.isFinite(minimum + maximum + reference + densityCoefficient) || minimum < 0
                || maximum > 100 || minimum >= maximum || reference < 0 || reference > 100
                || densityCoefficient < 0 || densityCoefficient > .1 || distribution == null
                || clear == null || rain == null || thunder == null || phases.isEmpty() || phases.size() > 32)
            throw new IllegalArgumentException("Invalid humidity settings");
        int next = 0;
        for (var phase : phases) {
            if (phase.start != next || phase.end <= phase.start || phase.end > 24000
                    || !Double.isFinite(phase.mean) || phase.mean < minimum || phase.mean > maximum)
                throw new IllegalArgumentException("Humidity phases must cover 0..24000 without gaps/overlaps, with means inside limits");
            next = phase.end;
        }
        if (next != 24000) throw new IllegalArgumentException("Humidity phases must end at 24000");
    }
    public Weather weather(int index) { return !weatherEffects ? clear : index == 2 ? thunder : index == 1 ? rain : clear; }
    public static List<Phase> parsePhases(List<? extends String> entries) {
        var result = new ArrayList<Phase>();
        for (String entry : entries) {
            var parts = entry.split("\\|", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Expected startTick|endTick|meanRH");
            result.add(new Phase(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()), Double.parseDouble(parts[2].trim())));
        }
        return List.copyOf(result);
    }
}
