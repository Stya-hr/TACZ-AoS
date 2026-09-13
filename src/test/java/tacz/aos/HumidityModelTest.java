package tacz.aos;

import java.util.List;
import org.junit.jupiter.api.Test;
import tacz.aos.humidity.*;
import static org.junit.jupiter.api.Assertions.*;

class HumidityModelTest {
    private static HumiditySettings settings(int smoothing, HumiditySettings.Distribution distribution,
                                             List<HumiditySettings.Phase> phases) {
        var d = HumiditySettings.DEFAULT;
        return new HumiditySettings(true, true, 0, 4000, smoothing, 5, 99, 50, d.densityCoefficient(),
                distribution, phases, d.clear(), d.rain(), d.thunder());
    }
    @Test void fixedAirCorrectionIsSmallAndHumidityReducesDrag() {
        var d = HumiditySettings.DEFAULT;
        assertEquals(1, HumidityModel.densityRatio(50, d));
        assertEquals(.9960559, HumidityModel.densityRatio(95, d), 1e-7);
        assertEquals(.023740457, HumidityModel.friction(.023833333f, HumidityModel.densityRatio(95, d)), 3e-9);
        assertTrue(HumidityModel.friction(.03f, HumidityModel.densityRatio(30, d)) > .03f);
        assertEquals(.03f, HumidityModel.friction(.03f, HumidityModel.densityRatio(95, HumiditySettings.DISABLED)));
        assertEquals(0, HumidityModel.friction(0, .99));
        assertEquals(1, HumidityModel.friction(1, .99));
    }
    @Test void slotsAndDaysAreDeterministicWithoutPlayerState() {
        var d = settings(0, HumiditySettings.Distribution.TRIANGULAR, HumiditySettings.DEFAULT.phases());
        assertEquals(HumidityModel.sample(7, 500, 0, d), HumidityModel.sample(7, 3999, 0, d));
        assertNotEquals(HumidityModel.sample(7, 500, 0, d), HumidityModel.sample(7, 24500, 0, d));
        assertNotEquals(HumidityModel.sample(7, 500, 0, d), HumidityModel.sample(8, 500, 0, d));
        assertNotEquals(HumidityModel.sample(7, 500, 0, d), HumidityModel.sample(7, 4500, 0, d));
    }
    @Test void configuredPhasesAndWeatherHaveCorrectMeansAndBounds() {
        var d = settings(0, HumiditySettings.Distribution.TRIANGULAR, HumiditySettings.DEFAULT.phases());
        for (int weather = 0; weather < 3; weather++) for (var phase : d.phases()) {
            double sum = 0;
            for (int day = 0; day < 10000; day++) {
                double v = HumidityModel.sample(421, day * 24000L + phase.start(), weather, d);
                assertTrue(v >= d.minimum() && v <= d.maximum()); sum += v;
            }
            double mean = phase.mean() + d.weather(weather).saturationBias() * (100 - phase.mean());
            assertEquals(mean, sum / 10000, .12);
        }
    }
    @Test void phaseSlotAndMidnightTransitionsAreContinuous() {
        var d = HumiditySettings.DEFAULT;
        for (int time : new int[] {0, 4000, 6000, 10000, 14000, 16000, 20000, 24000}) {
            assertEquals(HumidityModel.sample(31, time - 1, 0, d), HumidityModel.sample(31, time, 0, d), 1e-10);
            double from = HumidityModel.sample(31, time, 0, d), to = HumidityModel.sample(31, time + 200, 0, d);
            assertEquals((from + to) / 2, HumidityModel.sample(31, time + 100, 0, d), 1e-9);
        }
    }
    @Test void malformedSchedulesRejectGapsOverlapAndInvalidMeans() {
        for (var phases : List.of(List.of("0|100|50"), List.of("0|6000|50", "6001|24000|50"),
                List.of("0|7000|50", "6000|24000|50"), List.of("0|24000|NaN"), List.of("0|24000|101"))) {
            assertThrows(IllegalArgumentException.class, () -> settings(0, HumiditySettings.Distribution.TRIANGULAR, HumiditySettings.parsePhases(phases)));
        }
    }
    @Test void customDistributionAndBoundaryMeansRemainBounded() {
        var d = settings(0, HumiditySettings.Distribution.UNIFORM,
                List.of(new HumiditySettings.Phase(0, 12000, 5), new HumiditySettings.Phase(12000, 24000, 99)));
        assertEquals(5, HumidityModel.sample(0, 0, 0, d));
        assertEquals(99, HumidityModel.sample(0, 12000, 0, d));
    }
}
