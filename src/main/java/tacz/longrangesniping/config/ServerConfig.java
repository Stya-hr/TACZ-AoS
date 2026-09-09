package tacz.longrangesniping.config;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import tacz.longrangesniping.TaczLongRangeSniping;

import java.util.List;

public final class ServerConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> GUNS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> SCOPES;
    private static final ModConfigSpec.IntValue MAX_DISTANCE;
    private static final ModConfigSpec.IntValue MAX_ENTITIES;
    private static final ModConfigSpec.IntValue CACHE_MIB;
    private static final ModConfigSpec.BooleanValue DIAGNOSTICS;
    private static final ModConfigSpec.BooleanValue PROTOTYPE;
    private static final ModConfigSpec.IntValue PROTOTYPE_DISTANCE;
    private static final ModConfigSpec.IntValue MAX_BULLETS;
    private static final ModConfigSpec.IntValue MAX_FLIGHT_TICKS;

    public record Prototype(boolean enabled, int maxDistance, int maxActiveBullets, int maxFlightTicks) {}

    public record Settings(boolean available, EligibilityPolicy policy, int maxDistance,
                           int maxTrackedEntities, int snapshotMemoryMiB, boolean diagnostics, Prototype prototype) {}

    private static final Settings UNAVAILABLE = new Settings(false, EligibilityPolicy.disabled(), 1024, 32, 128, false,
            new Prototype(false, 128, 128, 600));
    private static volatile Settings current = UNAVAILABLE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("eligibility");
        ENABLED = builder.comment("Allow qualified shots to use the enabled sniping features.")
                .define("enabled", true);
        GUNS = builder.comment("Allowed TACZ gun IDs (namespace:path). Empty means no guns are allowed.")
                .defineListAllowEmpty("guns", EligibilityPolicy.DEFAULT_GUNS, () -> "", value -> value instanceof String);
        SCOPES = builder.comment("One rule per scope: scope_id|ordered zoom levels|allowed zooms.",
                        "The complete ordered layout must match the gun pack's display zoom array, including low-power sights.",
                        "Example: tacz:scope_mk5hd|5,25,1.25|25. Empty means no scopes are allowed.",
                        "Malformed rules disable eligibility until corrected; never infer magnification from the scope name.")
                .defineListAllowEmpty("scopeRules", EligibilityPolicy.DEFAULT_SCOPES, () -> "", value -> value instanceof String);
        builder.pop();
        builder.comment("Shared limits. Entity and snapshot budgets are reserved for later stages.")
                .push("limits");
        MAX_DISTANCE = builder.defineInRange("maxDistance", 1024, 16, 16384);
        MAX_ENTITIES = builder.defineInRange("maxTrackedEntities", 32, 1, 256);
        CACHE_MIB = builder.defineInRange("snapshotMemoryMiB", 128, 16, 4096);
        builder.pop();
        builder.comment("Experimental loaded-area ballistic scheduler. No remote terrain or entity rendering yet.")
                .push("prototype");
        PROTOTYPE = builder.comment("Opt in to taking over ordinary kinetic bullets. Original tracer entities are not spawned.")
                .define("enabled", false);
        PROTOTYPE_DISTANCE = builder.comment("Maximum traveled path length, also bounded by limits.maxDistance.",
                        "Stops before a whole tick that would exceed the limit; does not change TACZ gun parameters.")
                .defineInRange("maxDistance", 128, 16, 256);
        MAX_BULLETS = builder.comment("Maximum active bullets per dimension. Excess shots retain native TACZ behavior.")
                .defineInRange("maxActiveBullets", 128, 1, 1024);
        MAX_FLIGHT_TICKS = builder.comment("Scheduler safety timeout; the native bullet lifetime is also preserved.")
                .defineInRange("maxFlightTicks", 600, 1, 2400);
        builder.pop();
        builder.push("diagnostics");
        DIAGNOSTICS = builder.comment("Log eligibility changes, bullet parameters, takeover and termination reasons.")
                .define("enabled", false);
        builder.pop();
        SPEC = builder.build();
    }

    private ServerConfig() {}

    public static Settings current() { return current; }

    public static void onLoad(ModConfigEvent.Loading event) { reload(event); }
    public static void onReload(ModConfigEvent.Reloading event) { reload(event); }

    public static void onUnload(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == SPEC) current = UNAVAILABLE;
    }

    private static void reload(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        try {
            current = new Settings(true, EligibilityPolicy.parse(ENABLED.get(), GUNS.get(), SCOPES.get()),
                    MAX_DISTANCE.get(), MAX_ENTITIES.get(), CACHE_MIB.get(), DIAGNOSTICS.get(),
                    new Prototype(PROTOTYPE.get(), Math.min(MAX_DISTANCE.get(), PROTOTYPE_DISTANCE.get()),
                            MAX_BULLETS.get(), MAX_FLIGHT_TICKS.get()));
        } catch (IllegalArgumentException exception) {
            current = UNAVAILABLE;
            TaczLongRangeSniping.LOGGER.error("Invalid sniping eligibility config; eligibility disabled: {}", exception.getMessage());
        }
    }
}
