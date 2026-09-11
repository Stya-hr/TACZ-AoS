package tacz.aos;

import org.junit.jupiter.api.Test;
import tacz.aos.scoping.ScopeLoadThrottle;
import static org.junit.jupiter.api.Assertions.*;

class ScopeLoadThrottleTest {
    @Test void firstStableAimHasNoExtraDelay() {
        var t=new ScopeLoadThrottle();assertTrue(t.tryCheck(0,5));assertTrue(t.canStart(0,10));
    }
    @Test void failedChecksAreThrottledWithoutConsumingCreationCooldown() {
        var t=new ScopeLoadThrottle();assertTrue(t.tryCheck(100,5));
        for(int tick=101;tick<105;tick++)assertFalse(t.tryCheck(tick,5));
        assertTrue(t.tryCheck(105,5));assertTrue(t.canStart(105,10));
    }
    @Test void RenewalsCanBeCheckedDuringNewRegionCooldown() {
        var t=new ScopeLoadThrottle();t.tryCheck(0,5);t.started(0);
        assertTrue(t.tryCheck(5,5));assertFalse(t.canStart(5,10));
        assertTrue(t.canStart(10,10)); // Rechecking/renewing did not postpone creation.
    }
    @Test void ConfigurationChangesApplyToExistingHistory() {
        var t=new ScopeLoadThrottle();t.tryCheck(20,5);t.started(20);
        assertFalse(t.tryCheck(24,5));assertTrue(t.tryCheck(24,2));
        assertFalse(t.canStart(25,10));assertTrue(t.canStart(25,5));assertTrue(t.canStart(25,0));
    }
    @Test void PlayerLimitsAreIndependentAndDoNotRefillOnRepeatedQueries() {
        var a=new ScopeLoadThrottle();var b=new ScopeLoadThrottle();a.started(30);
        assertFalse(a.canStart(39,10));assertTrue(b.canStart(39,10));assertFalse(a.canStart(39,10));
    }
}
