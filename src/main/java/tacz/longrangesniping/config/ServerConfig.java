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

    public record Settings(boolean available, EligibilityPolicy policy, int maxDistance,
                           int maxTrackedEntities, int snapshotMemoryMiB, boolean diagnostics) {}

    private static final Settings UNAVAILABLE = new Settings(false, EligibilityPolicy.disabled(), 1024, 32, 128, false);
    private static volatile Settings current = UNAVAILABLE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("eligibility");
        ENABLED = builder.comment("Enable eligibility checks. This development stage does not modify bullet behavior.")
                .define("enabled", true);
        GUNS = builder.comment("Allowed TACZ gun IDs (namespace:path). Empty means no guns are allowed.")
                .defineListAllowEmpty("guns", EligibilityPolicy.DEFAULT_GUNS, () -> "", value -> value instanceof String);
        SCOPES = builder.comment("One rule per scope: scope_id|ordered zoom levels|allowed zooms.",
                        "The complete ordered layout must match the gun pack's display zoom array, including low-power sights.",
                        "Example: tacz:scope_mk5hd|5,25,1.25|25. Empty means no scopes are allowed.",
                        "Malformed rules disable eligibility until corrected; never infer magnification from the scope name.")
                .defineListAllowEmpty("scopeRules", EligibilityPolicy.DEFAULT_SCOPES, () -> "", value -> value instanceof String);
        builder.pop();
        builder.comment("Budgets reserved for later observation, cache and ballistic stages; not enforced by this stage.")
                .push("limits");
        MAX_DISTANCE = builder.defineInRange("maxDistance", 1024, 16, 16384);
        MAX_ENTITIES = builder.defineInRange("maxTrackedEntities", 32, 1, 256);
        CACHE_MIB = builder.defineInRange("snapshotMemoryMiB", 128, 16, 4096);
        builder.pop();
        builder.push("diagnostics");
        DIAGNOSTICS = builder.comment("Log changes in eligibility and eligible bullet spawn parameters. Does not change shots.")
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
                    MAX_DISTANCE.get(), MAX_ENTITIES.get(), CACHE_MIB.get(), DIAGNOSTICS.get());
        } catch (IllegalArgumentException exception) {
            current = UNAVAILABLE;
            TaczLongRangeSniping.LOGGER.error("Invalid sniping eligibility config; eligibility disabled: {}", exception.getMessage());
        }
    }
}
