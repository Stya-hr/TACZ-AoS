package tacz.aos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tacz.aos.config.EligibilityPolicy;
import tacz.aos.config.ScopeRule;
import tacz.aos.eligibility.Eligibility;
import tacz.aos.eligibility.Eligibility.Reason;
import tacz.aos.eligibility.Eligibility.WeaponState;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EligibilityTest {
    private static final EligibilityPolicy DEFAULT = EligibilityPolicy.parse(true,
            EligibilityPolicy.DEFAULT_GUNS, EligibilityPolicy.DEFAULT_SCOPES);

    @ParameterizedTest
    @CsvSource({
            "true,true,tacz:ai_awp,tacz:scope_mk5hd,1,ELIGIBLE",
            "true,false,tacz:ai_awp,tacz:scope_mk5hd,1,NOT_AIMING",
            "false,true,tacz:ai_awp,tacz:scope_mk5hd,1,INACTIVE_PLAYER",
            "true,true,'',tacz:scope_mk5hd,1,NOT_A_GUN",
            "true,true,tacz:ak47,tacz:scope_mk5hd,1,GUN_NOT_ALLOWED",
            "true,true,tacz:ai_awp,'',1,NO_SCOPE",
            "true,true,tacz:ai_awp,tacz:scope_acog_ta31,0,SCOPE_NOT_ALLOWED",
            "true,true,tacz:kar98,tacz:scope_98k,0,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_mk5hd,0,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_mk5hd,2,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_mk5hd,4,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_mk5hd,2147483647,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_mk5hd,-1,INVALID_ZOOM_NUMBER",
            "true,true,tacz:ai_awp,tacz:scope_vudu,0,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_vudu,1,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_lpvo_1_6,0,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_standard_8x,0,ELIGIBLE",
            "true,true,tacz:ai_awp,tacz:scope_standard_8x,1,ELIGIBLE",
            "true,true,tacz:springfield1873,tacz:scope_1873_6x,0,ELIGIBLE"
    })
    void allConditionsAreRequired(boolean active, boolean aiming, String gun, String scope, int counter, Reason expected) {
        assertEquals(expected, Eligibility.evaluate(DEFAULT, new WeaponState(active, aiming, gun, scope, counter)).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "scope|6|6", "TACZ:scope|6|6", "tacz:scope||6",
            "tacz:scope|NaN|NaN",
            "tacz:scope|Infinity|Infinity", "tacz:scope|-6|-6", "tacz:scope|0|0",
            "tacz:scope|6,|6", "tacz:scope|6|6|8"
    })
    void malformedScopeRulesAreRejected(String rule) {
        assertThrows(IllegalArgumentException.class, () -> ScopeRule.parse(rule));
    }

    @Test void idOnlyAndLegacyZoomRestrictionsAllowEverySlot() {
        for (String entry : List.of("tacz:scope_mk5hd", "tacz:scope_mk5hd|5,25,1.25", "tacz:scope_mk5hd|5,25,1.25|25")) {
            var rule = ScopeRule.parse(entry);
            for (int i = 0; i < 9; i++) assertTrue(rule.allows(i));
        }
        assertEquals("custom:scope", ScopeRule.parse("custom:scope").scopeId());
    }

    @Test void decimalZoomsAllowFloatRoundingButRejectReorderedLayouts() {
        ScopeRule rule = ScopeRule.parse(" pack:scope | 6.3,1.35 | 6.3 ");
        assertTrue(rule.matchesLayout(new float[] {6.3f, 1.35f}));
        assertFalse(rule.matchesLayout(new float[] {1.35f, 6.3f}));
        assertFalse(rule.matchesLayout(new float[] {6.3f}));
        assertFalse(rule.matchesLayout(new float[] {Float.NaN, 1.35f}));
        assertFalse(rule.matchesLayout(null));
        assertEquals(6.3, rule.zoom(2));
    }

    @Test void duplicateIdsAndMalformedGunIdsRejectTheWholePolicy() {
        assertThrows(IllegalArgumentException.class, () -> EligibilityPolicy.parse(true,
                List.of("tacz:ai_awp", " tacz:ai_awp "), List.of()));
        assertThrows(IllegalArgumentException.class, () -> EligibilityPolicy.parse(true,
                List.of("tacz:ai_awp"), List.of("tacz:scope|6|6", "tacz:scope|8|8")));
        assertThrows(IllegalArgumentException.class, () -> EligibilityPolicy.parse(true,
                List.of("tacz:*"), List.of()));
    }

    @Test void emptyListsDenyAllAndDisabledOverridesEquipment() {
        WeaponState state = new WeaponState(true, true, "tacz:ai_awp", "tacz:scope_mk5hd", 1);
        assertEquals(Reason.GUN_NOT_ALLOWED, Eligibility.evaluate(EligibilityPolicy.parse(true, List.of(), List.of()), state).reason());
        assertEquals(Reason.SCOPE_NOT_ALLOWED, Eligibility.evaluate(EligibilityPolicy.parse(true,
                EligibilityPolicy.DEFAULT_GUNS, List.of()), state).reason());
        assertEquals(Reason.DISABLED, Eligibility.evaluate(EligibilityPolicy.disabled(), state).reason());
    }

    @Test void switchingAndUnscopingDoNotRetainEligibilityOrMutateEarlierDecision() {
        var original = Eligibility.evaluate(DEFAULT, new WeaponState(true, true, "tacz:ai_awp", "tacz:scope_mk5hd", 1));
        assertTrue(original.eligible());
        assertFalse(Eligibility.evaluate(DEFAULT, new WeaponState(true, false, "tacz:ai_awp", "tacz:scope_mk5hd", 1)).eligible());
        assertTrue(Eligibility.evaluate(DEFAULT, new WeaponState(true, true, "tacz:ai_awp", "tacz:scope_mk5hd", 2)).eligible());
        assertFalse(Eligibility.evaluate(DEFAULT, new WeaponState(true, true, "tacz:ak47", "tacz:scope_mk5hd", 1)).eligible());
        assertTrue(original.eligible());
        assertEquals(25, original.zoom());
    }
}
