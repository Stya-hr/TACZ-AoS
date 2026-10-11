package tacz.aos.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable and independent of Minecraft, so config parsing can be tested without a game. */
public record EligibilityPolicy(boolean enabled, Set<String> gunIds, Map<String, ScopeRule> scopeRules) {
    // Defaults audited against the built-in gun pack in official TACZ Forge 1.20.1, 1.1.7.\n    // Kar98 and scope_98k are not part of the TaCZ 1.1.7 bundled pack.
    public static final List<String> DEFAULT_GUNS = List.of(
            "tacz:ai_awp", "tacz:m95", "tacz:m700", "tacz:m107", "tacz:springfield1873");
    public static final List<String> DEFAULT_SCOPES = List.of(
            "tacz:scope_mk5hd|5,25,1.25",
            "tacz:scope_vudu|6.5,1.35",
            "tacz:scope_lpvo_1_6|6.25,1.25",
            "tacz:scope_standard_8x|4.5,10",
            "tacz:scope_1873_6x|6");

    public EligibilityPolicy {
        gunIds = Set.copyOf(gunIds);
        scopeRules = Map.copyOf(scopeRules);
    }

    public static EligibilityPolicy parse(boolean enabled, List<? extends String> guns, List<? extends String> scopes) {
        Set<String> ids = new HashSet<>();
        for (String raw : guns) {
            String id = raw.trim();
            ScopeRule.requireId(id);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate gun ID: " + id);
            }
        }
        Map<String, ScopeRule> rules = new HashMap<>();
        for (String raw : scopes) {
            ScopeRule rule = ScopeRule.parse(raw);
            if (rules.putIfAbsent(rule.scopeId(), rule) != null) {
                throw new IllegalArgumentException("Duplicate scope rule: " + rule.scopeId());
            }
        }
        return new EligibilityPolicy(enabled, ids, rules);
    }

    public static EligibilityPolicy disabled() {
        return new EligibilityPolicy(false, Set.of(), Map.of());
    }
}
