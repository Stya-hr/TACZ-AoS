package tacz.aos;

import org.junit.jupiter.api.Test;
import tacz.aos.wind.WindModel;
import tacz.aos.wind.WindSettings;
import tacz.aos.wind.WindVector;

import static org.junit.jupiter.api.Assertions.*;

class WindModelTest {
    @Test void intervalDaySeedAndWeatherAreDeterministic() {
        var settings = WindSettings.DEFAULT;
        var first = WindModel.sample(12345, 0, 0, settings);
        assertEquals(first, WindModel.sample(12345, 3999, 0, settings));
        assertNotEquals(first, WindModel.sample(12345, 4000, 0, settings));
        assertNotEquals(first, WindModel.sample(12345, 24000, 0, settings));
        assertNotEquals(first, WindModel.sample(54321, 0, 0, settings));
        assertEquals(WindModel.sample(1, 24001, 1, settings), WindModel.sample(1, 25999, 1, settings));
        assertNotEquals(WindModel.sample(1, 25999, 1, settings), WindModel.sample(1, 26000, 1, settings));
        assertEquals(WindModel.sample(1, 1000, 2, settings), WindModel.sample(1, 1999, 2, settings));
        assertNotEquals(WindModel.sample(1, 1999, 2, settings), WindModel.sample(1, 2000, 2, settings));
        var plain = new WindSettings(true, false, 0, settings.clear(), settings.rain(), settings.thunder());
        assertEquals(first, WindModel.sample(12345, 0, 2, plain));
        assertEquals(WindVector.ZERO, WindModel.sample(12345, 0, 2, WindSettings.DISABLED));
        var salted = new WindSettings(true, true, 1, settings.clear(), settings.rain(), settings.thunder());
        assertNotEquals(first, WindModel.sample(12345, 0, 0, salted));
    }

    @Test void speedIsBoundedTypicalWindsAreModerateAndBearingsAreUnbiased() {
        // Analytic means of the default Rayleigh distributions after applying their hard caps.
        double[] truncatedMeans = {2.9221565359363284, 5.398922868396719, 8.328455723809903};
        for (int weather = 0; weather < 3; weather++) {
            double sum = 0, x = 0, z = 0;
            int[] quadrants = new int[4];
            for (int day = 0; day < 10000; day++) {
                var wind = WindModel.sample(7, day * 24000L, weather, WindSettings.DEFAULT);
                assertTrue(wind.speed() <= WindSettings.DEFAULT.profile(weather).maxSpeed() + 1e-10);
                sum += wind.speed(); x += wind.x(); z += wind.z();
                quadrants[(wind.x() >= 0 ? 1 : 0) + (wind.z() >= 0 ? 2 : 0)]++;
            }
            assertEquals(truncatedMeans[weather], sum / 10000, .25);
            assertEquals(0, x / 10000, .15); assertEquals(0, z / 10000, .15);
            for (int count : quadrants) assertTrue(count > 2300 && count < 2700);
        }
    }

    @Test void crosswindDropsParallelComponentAndArrowTracksScreenRight() {
        var wind = new WindVector(3, 4);
        assertEquals(new WindVector(0, 4), wind.perpendicular(1, 0));
        assertEquals(4, wind.signedCrosswind(1, 0));
        assertEquals(-4, wind.signedCrosswind(-1, 0));
        assertEquals(-3, wind.signedCrosswind(0, 1));
        assertEquals(0, wind.signedCrosswind(3, 4), 1e-10);
        var cross = wind.perpendicular(.3, .7);
        assertEquals(0, cross.x() * .3 + cross.z() * .7, 1e-10);
        assertTrue(cross.speed() <= wind.speed());
        assertEquals(WindVector.ZERO, wind.perpendicular(0, 0));
    }

    @Test void invalidProfilesAndVectorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WindSettings.Profile(0, 2, 5));
        assertThrows(IllegalArgumentException.class, () -> new WindSettings.Profile(4000, 6, 5));
        assertThrows(IllegalArgumentException.class, () -> new WindSettings.Profile(4000, 6, 31));
        assertThrows(IllegalArgumentException.class, () -> new WindSettings.Profile(4000, Double.NaN, 5));
        assertThrows(IllegalArgumentException.class, () -> new WindVector(Double.NaN, 0));
    }
}
