package tacz.longrangesniping.config;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Server-owned mapping; the order must match the gun pack's client zoom array. */
public record ScopeRule(String scopeId, List<Double> zoomLevels, List<Double> allowedZooms) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final double EPSILON = 0.0001;

    public ScopeRule {
        requireId(scopeId);
        zoomLevels = List.copyOf(zoomLevels);
        allowedZooms = List.copyOf(allowedZooms);
        if (zoomLevels.isEmpty() || zoomLevels.size() > 64 || allowedZooms.isEmpty()) {
            throw new IllegalArgumentException("A scope needs 1-64 zoom levels and at least one allowed zoom: " + scopeId);
        }
        zoomLevels.forEach(ScopeRule::requireZoom);
        allowedZooms.forEach(ScopeRule::requireZoom);
        for (double allowed : allowedZooms) {
            if (zoomLevels.stream().noneMatch(zoom -> sameZoom(zoom, allowed))) {
                throw new IllegalArgumentException("Allowed zoom " + allowed + " is not in the layout for " + scopeId);
            }
        }
    }

    public static ScopeRule parse(String entry) {
        String[] parts = entry.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected scope_id|ordered zoom levels|allowed zooms: " + entry);
        }
        return new ScopeRule(parts[0].trim(), parseZooms(parts[1]), parseZooms(parts[2]));
    }

    public int slot(int zoomNumber) {
        if (zoomNumber < 0) {
            throw new IllegalArgumentException("Negative TACZ ZoomNumber");
        }
        return zoomNumber % zoomLevels.size();
    }

    public double zoom(int zoomNumber) {
        return zoomLevels.get(slot(zoomNumber));
    }

    public boolean allows(int zoomNumber) {
        double current = zoom(zoomNumber);
        return allowedZooms.stream().anyMatch(allowed -> sameZoom(current, allowed));
    }

    public boolean matchesLayout(float[] actual) {
        if (actual == null || actual.length != zoomLevels.size()) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (!sameZoom(actual[i], zoomLevels.get(i))) {
                return false;
            }
        }
        return true;
    }

    public static void requireId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Expected a lowercase namespace:path ID: " + id);
        }
    }

    private static List<Double> parseZooms(String values) {
        return Arrays.stream(values.split(",", -1)).map(String::trim).map(Double::parseDouble).toList();
    }

    private static void requireZoom(double zoom) {
        if (!Double.isFinite(zoom) || zoom <= 0) {
            throw new IllegalArgumentException("Zoom must be finite and positive: " + zoom);
        }
    }

    private static boolean sameZoom(double left, double right) {
        return Double.isFinite(left) && Double.isFinite(right) && Math.abs(left - right) <= EPSILON;
    }
}
