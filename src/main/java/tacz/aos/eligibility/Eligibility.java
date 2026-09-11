package tacz.aos.eligibility;

import tacz.aos.config.EligibilityPolicy;
import tacz.aos.config.ScopeRule;

/** Eligibility only: no world queries, entity subscriptions, or ballistic side effects. */
public final class Eligibility {
    private Eligibility() {}

    public enum Reason {
        ELIGIBLE, CONFIG_UNAVAILABLE, DISABLED, INACTIVE_PLAYER, NOT_A_GUN,
        GUN_NOT_ALLOWED, GUN_DATA_UNAVAILABLE, NO_SCOPE, SCOPE_NOT_ALLOWED,
        SCOPE_DATA_UNAVAILABLE, INVALID_ZOOM_NUMBER, ZOOM_NOT_ALLOWED, NOT_AIMING,
        GUN_OPERATOR_UNAVAILABLE, CLIENT_LAYOUT_MISMATCH, NO_NEARBY_RANGEFINDER
    }

    public record WeaponState(boolean active, boolean aiming, String gunId, String scopeId, int zoomNumber) {}

    public record Decision(Reason reason, String gunId, String scopeId, int slot, double zoom) {
        public boolean eligible() { return reason == Reason.ELIGIBLE; }

        public Decision withReason(Reason replacement) {
            return new Decision(replacement, gunId, scopeId, slot, zoom);
        }

        public static Decision denied(Reason reason) {
            return new Decision(reason, "", "", -1, 0);
        }
    }

    public static Decision evaluate(EligibilityPolicy policy, WeaponState state) {
        Decision base = new Decision(Reason.ELIGIBLE, state.gunId(), state.scopeId(), -1, 0);
        if (!policy.enabled()) return base.withReason(Reason.DISABLED);
        if (!state.active()) return base.withReason(Reason.INACTIVE_PLAYER);
        if (state.gunId().isEmpty()) return base.withReason(Reason.NOT_A_GUN);
        if (!policy.gunIds().contains(state.gunId())) return base.withReason(Reason.GUN_NOT_ALLOWED);
        if (state.scopeId().isEmpty()) return base.withReason(Reason.NO_SCOPE);
        ScopeRule rule = policy.scopeRules().get(state.scopeId());
        if (rule == null) return base.withReason(Reason.SCOPE_NOT_ALLOWED);
        if (state.zoomNumber() < 0) return base.withReason(Reason.INVALID_ZOOM_NUMBER);
        base = new Decision(Reason.ELIGIBLE, state.gunId(), state.scopeId(),
                rule.slot(state.zoomNumber()), rule.zoom(state.zoomNumber()));
        if (!state.aiming()) return base.withReason(Reason.NOT_AIMING);
        return base;
    }
}
