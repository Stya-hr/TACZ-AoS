package tacz.aos;

import org.junit.jupiter.api.Test;
import tacz.aos.trails.GunshotAcoustics;
import static org.junit.jupiter.api.Assertions.*;

class GunshotAcousticsTest {
    @Test void arrivalUsesSoundSpeedAndRoundsUpInsteadOfPlayingEarly() {
        assertEquals(20, GunshotAcoustics.delayTicks(343, 343));
        assertEquals(59, GunshotAcoustics.delayTicks(1000, 343));
        assertEquals(120, GunshotAcoustics.delayTicks(2048, 343));
        assertEquals(40, GunshotAcoustics.delayTicks(343, 171.5));
    }
    @Test void moreDistantShotsAreQuieterAndMuteIsRespected() {
        float previous = 1;
        for (double distance : new double[]{128, 256, 512, 1000, 2048, 16384}) {
            float volume = GunshotAcoustics.volume(distance, .65);
            assertTrue(volume > 0 && volume < previous);
            assertEquals(0, GunshotAcoustics.volume(distance, 0));
            previous = volume;
        }
    }
}
