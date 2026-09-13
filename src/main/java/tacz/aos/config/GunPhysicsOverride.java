package tacz.aos.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Absolute muzzle speed (m/s) and fractional air speed loss per tick at reference humidity. */
public record GunPhysicsOverride(Float speed, Float friction) {
    public static final List<String> DEFAULTS = List.of(
            "tacz:ai_awp|speed=905|friction=0.023833333",
            "tacz:m95|speed=858|friction=0.014333333",
            "tacz:m107|speed=858|friction=0.014333333",
            "tacz:m700|speed=822.96|friction=0.030666667",
            "tacz:kar98|speed=780|friction=0.0275");

    public GunPhysicsOverride {
        if (speed == null && friction == null)
            throw new IllegalArgumentException("At least one physics property is required");
        check(speed, 5120, false, "speed");
        check(friction, 1, true, "friction");
        if (friction != null && friction >= 1) throw new IllegalArgumentException("friction must be < 1");
    }

    public float speedOr(float nativeValue) { return speed == null ? nativeValue : speed / 20f; }
    public float frictionOr(float nativeValue) { return friction == null ? nativeValue : friction; }

    public static Map<String, GunPhysicsOverride> parse(List<? extends String> entries) {
        if (entries.size() > 1024) throw new IllegalArgumentException("At most 1024 gun overrides are supported");
        Map<String, GunPhysicsOverride> result = new HashMap<>();
        for (String entry : entries) {
            if (entry.length() > 1024) throw new IllegalArgumentException("Gun override entry is too long");
            String[] parts = entry.split("\\|", -1);
            String id = parts[0].trim();
            ScopeRule.requireId(id);
            Map<String, Float> fields = new HashMap<>();
            for (int i = 1; i < parts.length; i++) {
                String[] pair = parts[i].split("=", -1);
                if (pair.length != 2) throw new IllegalArgumentException("Expected property=value: " + entry);
                String name = pair[0].trim();
                if (!List.of("speed", "friction").contains(name))
                    throw new IllegalArgumentException("Unknown gun physics property: " + name);
                if (fields.putIfAbsent(name, Float.parseFloat(pair[1].trim())) != null)
                    throw new IllegalArgumentException("Duplicate property: " + entry);
            }
            var profile = new GunPhysicsOverride(fields.get("speed"), fields.get("friction"));
            if (result.putIfAbsent(id, profile) != null) throw new IllegalArgumentException("Duplicate gun override: " + id);
        }
        return Map.copyOf(result);
    }

    private static void check(Float value, float maximum, boolean zeroAllowed, String name) {
        if (value != null && (!Float.isFinite(value) || value < 0 || !zeroAllowed && value == 0 || value > maximum))
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
    }
}
