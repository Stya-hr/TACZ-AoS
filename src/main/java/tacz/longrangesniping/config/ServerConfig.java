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
    private static final ModConfigSpec.BooleanValue BALLISTICS, EXTEND_LIFETIME;
    private static final ModConfigSpec.IntValue BALLISTICS_DISTANCE;
    private static final ModConfigSpec.IntValue MAX_BULLETS;
    private static final ModConfigSpec.IntValue MAX_FLIGHT_TICKS;
    private static final ModConfigSpec.BooleanValue SNAPSHOTS;
    private static final ModConfigSpec.IntValue SNAPSHOT_BLOCKS, SNAPSHOT_SECTIONS, REFRESH_DELAY, PENDING_SECTIONS, TRACKED_CHUNKS;
    private static final ModConfigSpec.DoubleValue SNAPSHOT_MILLIS;
    private static final ModConfigSpec.BooleanValue SCOPE_RENDERING;
    private static final ModConfigSpec.IntValue SCOPE_INTERVAL, SCOPE_CANDIDATES, SCOPE_TRACE_CELLS;
    private static final ModConfigSpec.BooleanValue ACTIVITY;
    private static final ModConfigSpec.IntValue ACTIVITY_RADIUS, ACTIVITY_STABLE, ACTIVITY_RETENTION,
            ACTIVITY_CHUNKS, ACTIVITY_DEPENDENCIES, ACTIVITY_REGIONS, ACTIVITY_STARTS, ACTIVITY_TIMEOUT, ACTIVITY_QUERIES;

    private static final ModConfigSpec.IntValue PREDICTION_INTERVAL, PREDICTION_ENTITY_CHECKS;
    private static final ModConfigSpec.DoubleValue PREDICTION_MILLIS;
    public record Prediction(int intervalTicks, double maxMillisPerTick, int maxEntityChecks) {}
    public static final Prediction DEFAULT_PREDICTION = new Prediction(8, 4.0, 8192);

    private static final ModConfigSpec.BooleanValue DISK;
    private static final ModConfigSpec.IntValue DISK_MIB, DISK_SECTIONS, IO_QUEUE, IO_QUEUE_MIB, IO_MIB, IO_OPS, RESTORE_SECTIONS, PIN_MIB, PIN_SECTIONS;
    public record Persistence(boolean enabled, int diskMiB, int maxDiskSections, int maxQueuedSections,
                              int queueMiB, int ioMiBPerSecond, int operationsPerSecond, int restoresPerTick,
                              int pinMiB, int maxPinnedSections) {}
    public static final Persistence DEFAULT_PERSISTENCE = new Persistence(true, 256, 65536, 128, 32, 8, 128, 8, 32, 2048);

    public record Ballistics(boolean enabled, int maxDistance, int maxActiveBullets, int maxFlightTicks, boolean extendLifetime) {
        public Ballistics(boolean enabled, int maxDistance, int maxActiveBullets, int maxFlightTicks) {
            this(enabled, maxDistance, maxActiveBullets, maxFlightTicks, false);
        }
    }
    public record Snapshots(boolean enabled, int blocksPerTick, int sectionsPerTick, double maxMillisPerTick,
                            int refreshDelayTicks, int maxPendingSections, int maxTrackedChunks) {}
    public static final Snapshots DEFAULT_SNAPSHOTS = new Snapshots(true, 4096, 16, 2.0, 5, 8192, 8192);
    public record ScopeRendering(boolean enabled, int intervalTicks, int maxCandidates, int maxTraceCells) {}
    public static final ScopeRendering DEFAULT_SCOPE_RENDERING = new ScopeRendering(true, 4, 256, 65536);
    public record Activity(boolean enabled, int radius, int stableTicks, int retentionTicks, int maxFullChunks,
                           int maxDependencyChunks, int maxRegions, int startsPerSecond, int loadTimeoutTicks, int maxQueriesPerTick) {
        public Activity(boolean enabled, int radius, int stableTicks, int retentionTicks, int maxFullChunks,
                        int maxDependencyChunks, int maxRegions, int startsPerSecond, int loadTimeoutTicks) {
            this(enabled, radius, stableTicks, retentionTicks, maxFullChunks, maxDependencyChunks, maxRegions,
                    startsPerSecond, loadTimeoutTicks, 2);
        }
    }
    public static final Activity DEFAULT_ACTIVITY = new Activity(true, 1, 8, 40, 196, 4096, 4, 2, 100);

    public record Settings(boolean available, EligibilityPolicy policy, int maxDistance,
                           int maxTrackedEntities, int snapshotMemoryMiB, boolean diagnostics, Ballistics ballistics,
                           Snapshots snapshots, ScopeRendering scopeRendering, Activity activity, Persistence persistence, Prediction prediction) {
        public Settings(boolean available, EligibilityPolicy policy, int maxDistance, int maxTrackedEntities,
                        int snapshotMemoryMiB, boolean diagnostics, Ballistics ballistics, Snapshots snapshots,
                        ScopeRendering scopeRendering, Activity activity, Persistence persistence) {
            this(available, policy, maxDistance, maxTrackedEntities, snapshotMemoryMiB, diagnostics, ballistics,
                    snapshots, scopeRendering, activity, persistence, DEFAULT_PREDICTION);
        }
        public Settings(boolean available, EligibilityPolicy policy, int maxDistance, int maxTrackedEntities,
                        int snapshotMemoryMiB, boolean diagnostics, Ballistics ballistics, Snapshots snapshots,
                        ScopeRendering scopeRendering, Activity activity) {
            this(available, policy, maxDistance, maxTrackedEntities, snapshotMemoryMiB, diagnostics, ballistics,
                    snapshots, scopeRendering, activity, DEFAULT_PERSISTENCE);
        }
        public Settings(boolean available, EligibilityPolicy policy, int maxDistance, int maxTrackedEntities,
                        int snapshotMemoryMiB, boolean diagnostics, Ballistics ballistics, Snapshots snapshots,
                        ScopeRendering scopeRendering) {
            this(available, policy, maxDistance, maxTrackedEntities, snapshotMemoryMiB, diagnostics, ballistics,
                    snapshots, scopeRendering, DEFAULT_ACTIVITY);
        }
        public Settings(boolean available, EligibilityPolicy policy, int maxDistance, int maxTrackedEntities,
                        int snapshotMemoryMiB, boolean diagnostics, Ballistics ballistics, Snapshots snapshots) {
            this(available, policy, maxDistance, maxTrackedEntities, snapshotMemoryMiB, diagnostics, ballistics, snapshots,
                    DEFAULT_SCOPE_RENDERING);
        }
    }

    private static final Settings UNAVAILABLE = new Settings(false, EligibilityPolicy.disabled(), 1024, 32, 128, false,
            new Ballistics(false, 128, 128, 600), DEFAULT_SNAPSHOTS);
    private static volatile Settings current = UNAVAILABLE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("eligibility");
        ENABLED = builder.comment("Allow qualified shots to use the enabled sniping features.")
                .define("enabled", true);
        GUNS = builder.comment("Allowed TACZ gun IDs (namespace:path). Empty means no guns are allowed.")
                .defineListAllowEmpty("guns", EligibilityPolicy.DEFAULT_GUNS, () -> "", value -> value instanceof String);
        SCOPES = builder.comment("Scope ID whitelist. Every aiming magnification is allowed within 3 blocks of a placed rangefinder.",
                        "Entries: scope_id or scope_id|ordered zoom levels (optional server fallback projection metadata).",
                        "Legacy scope_id|ordered zoom levels|allowed zooms entries remain readable; the last field is ignored.",
                        "Client rendering uses the actual gun-pack zoom/view data. Empty means no scopes are allowed.")
                .defineListAllowEmpty("scopeRules", EligibilityPolicy.DEFAULT_SCOPES, () -> "", value -> value instanceof String);
        builder.pop();
        builder.comment("Shared limits. Entity budget applies per scoped player.")
                .push("limits");
        MAX_DISTANCE = builder.defineInRange("maxDistance", 1024, 16, 16384);
        MAX_ENTITIES = builder.defineInRange("maxTrackedEntities", 32, 1, 256);
        CACHE_MIB = builder.comment("Server-wide estimated memory budget for complete collision snapshots; evicted sections become unknown.")
                .defineInRange("snapshotMemoryMiB", 128, 16, 4096);
        builder.pop();
        builder.comment("Server-authoritative scoped kinetic bullets; unloaded corridors use collision snapshots.",
                        "Replaces the old prototype section. Legacy prototype settings no longer control this feature.")
                .push("ballistics");
        BALLISTICS = builder.comment("Take over eligible ordinary kinetic bullets. Does not spawn native tracer entities.")
                .define("enabled", true);
        BALLISTICS_DISTANCE = builder.comment("Maximum traveled path length. Zero follows limits.maxDistance; a positive value can only lower it.")
                .defineInRange("maxDistance", 0, 0, 16384);
        MAX_BULLETS = builder.comment("Maximum active bullets per dimension. Excess shots retain native TACZ behavior.")
                .defineInRange("maxActiveBullets", 128, 1, 1024);
        MAX_FLIGHT_TICKS = builder.comment("Hard flight duration cap. In-flight target-region leases use this same bounded deadline.")
                .defineInRange("maxFlightTicks", 600, 1, 2400);
        EXTEND_LIFETIME = builder.comment("Allow eligible bullets to outlive TACZ's native lifetime up to maxFlightTicks.",
                        "Speed, gravity, drag, damage falloff and the traveled-distance limit remain in force.")
                .define("extendLifetime", true);
        builder.pop();
        builder.comment("Collision snapshots with optional asynchronous persistence. Independent of ballistic takeover; never loads real chunks.")
                .push("snapshots");
        SNAPSHOTS = builder.define("enabled", true);
        SNAPSHOT_BLOCKS = builder.comment("Maximum sampled cells per server tick, shared across dimensions.")
                .defineInRange("blocksPerTick", 4096, 64, 65536);
        SNAPSHOT_SECTIONS = builder.comment("Maximum completed sections per tick; a palette-proven uniform section costs one cell work unit.")
                .defineInRange("sectionsPerTick", 16, 1, 256);
        SNAPSHOT_MILLIS = builder.comment("Soft acquisition time budget, checked every 64 cells; this is not a hard tick-time guarantee.")
                .defineInRange("maxMillisPerTick", 2.0, 0.1, 10.0);
        REFRESH_DELAY = builder.comment("Coalesce edits for this many server ticks. Invalidated sections are unknown until rebuilt.")
                .defineInRange("refreshDelayTicks", 5, 0, 200);
        PENDING_SECTIONS = builder.comment("Bound on queued section addresses; omitted work is retried for tracked loaded chunks.")
                .defineInRange("maxPendingSections", 8192, 64, 65536);
        TRACKED_CHUNKS = builder.comment("Bound on coordinate-only loaded-chunk index. Overflow forgets oldest tracked coordinates.")
                .defineInRange("maxTrackedChunks", 8192, 64, 65536);
        builder.pop();
        builder.comment("World-local collision cache. Reads/writes/compression run off-thread; disk-only sections are not yet usable.",
                        "Changes to these limits apply after restarting the server. A dirty shutdown discards the disk cache conservatively.")
                .push("persistence");
        DISK = builder.define("enabled", true);
        DISK_MIB = builder.defineInRange("diskMiB", 256, 16, 4096);
        DISK_SECTIONS = builder.defineInRange("maxDiskSections", 65536, 256, 262144);
        IO_QUEUE = builder.defineInRange("maxQueuedSections", 128, 8, 2048);
        IO_QUEUE_MIB = builder.defineInRange("queueMiB", 32, 8, 256);
        IO_MIB = builder.defineInRange("ioMiBPerSecond", 8, 1, 128);
        IO_OPS = builder.defineInRange("operationsPerSecond", 128, 8, 2048);
        RESTORE_SECTIONS = builder.defineInRange("restoresPerTick", 8, 1, 64);
        PIN_MIB = builder.comment("Subset of the memory cache reserved for in-flight snapshot leases, not extra memory.")
                .defineInRange("pinMiB", 32, 0, 1024);
        PIN_SECTIONS = builder.defineInRange("maxPinnedSections", 2048, 0, 32768);
        builder.pop();
        builder.comment("Additional entity tracking/rendering while near a rangefinder with an allowed gun/scope and aiming, at every zoom.",
                        "All entity types may be displayed; activity controls bounded server-side target loading.")
                .push("scopeRendering");
        SCOPE_RENDERING = builder.define("enabled", true);
        SCOPE_INTERVAL = builder.comment("Refresh subscriptions every N ticks; losing eligibility revokes them each tick.")
                .defineInRange("intervalTicks", 4, 2, 20);
        SCOPE_CANDIDATES = builder.comment("Maximum entity candidates inspected per refresh and player.")
                .defineInRange("maxCandidates", 256, 16, 2048);
        SCOPE_TRACE_CELLS = builder.comment("Maximum distinct collision cells read per refresh/player; exhausted checks fail closed.")
                .defineInRange("maxTraceCells", 65536, 1024, 1048576);
        builder.pop();
        builder.comment("Temporary real simulation around known scope targets; shared server-wide budgets.",
                        "Unknown sight lines never trigger loading. Full terrain and its loading halo require live/session evidence or valid collision cache records.")
                .push("activity");
        ACTIVITY = builder.define("enabled", true);
        ACTIVITY_RADIUS = builder.comment("Entity-ticking core radius in chunks; 1 means 3x3 core and 7x7 FULL footprint.")
                .defineInRange("radius", 1, 0, 2);
        ACTIVITY_STABLE = builder.defineInRange("stableTicks", 8, 2, 100);
        ACTIVITY_RETENTION = builder.comment("Scope release grace. Accepted in-flight bullets can retain their target regions beyond this grace.")
                .defineInRange("retentionTicks", 40, 1, 200);
        ACTIVITY_CHUNKS = builder.comment("Union of reserved FULL chunk footprints, including loading regions, release grace and in-flight shots.")
                .defineInRange("maxFullChunks", 196, 25, 2048);
        ACTIVITY_DEPENDENCIES = builder.comment("Additional conservative budget for the entire ticket dependency footprint (including FULL chunks).")
                .defineInRange("maxDependencyChunks", 4096, 256, 16384);
        ACTIVITY_REGIONS = builder.comment("Server-wide region limit. Each player may retain at most two regions including release grace and in-flight shots.")
                .defineInRange("maxRegions", 4, 1, 32);
        ACTIVITY_STARTS = builder.comment("Maximum new regions admitted in any rolling 20 server ticks.")
                .defineInRange("startsPerSecond", 2, 1, 20);
        ACTIVITY_TIMEOUT = builder.comment("Release tickets if the entire core does not become entity-ticking before this deadline.")
                .defineInRange("loadTimeoutTicks", 100, 20, 600);
        ACTIVITY_QUERIES = builder.comment("Server-wide ballistic impact predictions per tick, in addition to the entity visibility budget.")
                .defineInRange("maxQueriesPerTick", 2, 1, 16);
        builder.pop();
        builder.comment("Read-only TACZ mean-trajectory prediction; only the first known impact can admit a target region.",
                        "Uses live terrain/snapshots, never loads a flight corridor. Unsupported dynamic firing scripts skip prediction.")
                .push("prediction");
        PREDICTION_INTERVAL = builder.comment("Minimum ticks between predictions per player, rounded up to a subscription refresh.")
                .defineInRange("intervalTicks", 8, 2, 20);
        PREDICTION_MILLIS = builder.comment("Soft server-wide prediction budget per tick; checked every 64 collision lookups.",
                        "Exhausted predictions do not admit targets. Actual bullet simulation has its own budgets.")
                .defineInRange("maxMillisPerTick", 4.0, 0.25, 10.0);
        PREDICTION_ENTITY_CHECKS = builder.defineInRange("maxEntityChecks", 8192, 256, 65536);
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
                    new Ballistics(BALLISTICS.get(), BALLISTICS_DISTANCE.get() == 0 ? MAX_DISTANCE.get() : Math.min(MAX_DISTANCE.get(), BALLISTICS_DISTANCE.get()),
                            MAX_BULLETS.get(), MAX_FLIGHT_TICKS.get(), EXTEND_LIFETIME.get()),
                    new Snapshots(SNAPSHOTS.get(), SNAPSHOT_BLOCKS.get(), SNAPSHOT_SECTIONS.get(), SNAPSHOT_MILLIS.get(),
                            REFRESH_DELAY.get(), PENDING_SECTIONS.get(), TRACKED_CHUNKS.get()),
                    new ScopeRendering(SCOPE_RENDERING.get(), SCOPE_INTERVAL.get(), SCOPE_CANDIDATES.get(), SCOPE_TRACE_CELLS.get()),
                    new Activity(ACTIVITY.get(), ACTIVITY_RADIUS.get(), ACTIVITY_STABLE.get(), ACTIVITY_RETENTION.get(),
                            ACTIVITY_CHUNKS.get(), ACTIVITY_DEPENDENCIES.get(), ACTIVITY_REGIONS.get(), ACTIVITY_STARTS.get(),
                            ACTIVITY_TIMEOUT.get(), ACTIVITY_QUERIES.get()),
                    new Persistence(DISK.get(), DISK_MIB.get(), DISK_SECTIONS.get(), IO_QUEUE.get(), IO_QUEUE_MIB.get(),
                            IO_MIB.get(), IO_OPS.get(), RESTORE_SECTIONS.get(), PIN_MIB.get(), PIN_SECTIONS.get()),
                    new Prediction(PREDICTION_INTERVAL.get(), PREDICTION_MILLIS.get(), PREDICTION_ENTITY_CHECKS.get()));
        } catch (IllegalArgumentException exception) {
            current = UNAVAILABLE;
            TaczLongRangeSniping.LOGGER.error("Invalid sniping eligibility config; eligibility disabled: {}", exception.getMessage());
        }
    }
}
