package tacz.aos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tacz.aos.config.GunPhysicsOverride;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GunPhysicsOverrideTest {
    @Test void defaultsUseSIUnitsAndKeepSpringfieldNative() {
        var profiles = GunPhysicsOverride.parse(GunPhysicsOverride.DEFAULTS);
        assertEquals(5, profiles.size());
        assertFalse(profiles.containsKey("tacz:springfield1873"));
        assertEquals(45.25f, profiles.get("tacz:ai_awp").speedOr(57.5f));
        assertEquals(9.80665f / 400f, profiles.get("tacz:ai_awp").gravityOr(.15f));
    }

    @Test void thirdPartyIdsPartialPropertiesAndEmptyList() {
        var profile = GunPhysicsOverride.parse(List.of(" custom_pack:rifles/test | speed = 800 ")).get("custom_pack:rifles/test");
        assertEquals(40f, profile.speedOr(10f));
        assertEquals(.15f, profile.gravityOr(.15f));
        assertEquals(.031f, profile.frictionOr(.031f));
        assertTrue(GunPhysicsOverride.parse(List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> GunPhysicsOverride.parse(List.of("x:y|speed=1", "x:y|gravity=1")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x:y", "X:y|speed=2", "x:y|speed=0", "x:y|speed=-2", "x:y|speed=5121",
            "x:y|speed=NaN", "x:y|friction=Infinity", "x:y|gravity=-1", "x:y|gravity=401", "x:y|friction=1",
            "x:y|friction=-0.1", "x:y|speed=", "x:y|speed=1|speed=2", "x:y|damage=2", "x:y|gravity=1|"})
    void invalidEntriesAreRejected(String entry) {
        assertThrows(IllegalArgumentException.class, () -> GunPhysicsOverride.parse(List.of(entry)));
    }
}
