package tacz.aos.config;

import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import tacz.aos.TaczArtOfSniping;

import java.util.List;

public final class ServerConfig {
    public static final ForgeConfigSpec SPEC;
    private static final HumidityConfig HUMIDITY;
    private static volatile tacz.aos.humidity.HumiditySettings humidity = tacz.aos.humidity.HumiditySettings.DISABLED;
    public static tacz.aos.humidity.HumiditySettings humidity() { return humidity; }
    public record Occlusion(boolean enabled,int radius,int cellsPerPlayer,int cellsPerTick,int maxSections,int maxFaces,int refreshTicks,int sectionsPerPacket) {}
    public static final Occlusion DEFAULT_OCCLUSION=new Occlusion(true,96,32768,131072,128,100000,10,4);
    private static volatile Occlusion occlusion=DEFAULT_OCCLUSION;
    public static Occlusion occlusion(){return occlusion;}
    private static final ForgeConfigSpec.BooleanValue OCCLUSION_ENABLED;
    private static final ForgeConfigSpec.IntValue OCCLUSION_RADIUS,OCCLUSION_CELLS,OCCLUSION_GLOBAL,OCCLUSION_SECTIONS,OCCLUSION_FACES,OCCLUSION_REFRESH,OCCLUSION_PACKETS;

    public record DistantGunshots(boolean enabled, double speed, int minDistance, int maxDistance, double volume) {}
    private static final DistantGunshots SILENT_GUNSHOTS = new DistantGunshots(false, 343, 128, 0, .65);
    private static volatile DistantGunshots distantGunshots = SILENT_GUNSHOTS;
    private static final ForgeConfigSpec.BooleanValue DISTANT_SOUND;
    private static final ForgeConfigSpec.DoubleValue SOUND_SPEED, SOUND_VOLUME;
    private static final ForgeConfigSpec.IntValue SOUND_MIN, SOUND_MAX;
    public static DistantGunshots distantGunshots() { return distantGunshots; }
    private static final ForgeConfigSpec.BooleanValue WIND_ENABLED, WIND_WEATHER;
    private static final ForgeConfigSpec.LongValue WIND_SEED;
    private static final ForgeConfigSpec.IntValue[] WIND_PERIODS = new ForgeConfigSpec.IntValue[3];
    private static final ForgeConfigSpec.DoubleValue[] WIND_MEANS = new ForgeConfigSpec.DoubleValue[3], WIND_MAX = new ForgeConfigSpec.DoubleValue[3];
    private static volatile tacz.aos.wind.WindSettings wind = tacz.aos.wind.WindSettings.DISABLED;
    private static final ForgeConfigSpec.BooleanValue GUN_OVERRIDES_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUN_OVERRIDES;
    private static volatile java.util.Map<String, GunPhysicsOverride> gunOverrides = java.util.Map.of();
    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> GUNS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCOPES;
    private static final ForgeConfigSpec.IntValue MAX_DISTANCE;
    private static final ForgeConfigSpec.IntValue MAX_ENTITIES;
    private static final ForgeConfigSpec.BooleanValue DIAGNOSTICS;
    private static final ForgeConfigSpec.BooleanValue BALLISTICS, EXTEND_LIFETIME;
    private static final ForgeConfigSpec.IntValue BALLISTICS_DISTANCE;
    private static final ForgeConfigSpec.IntValue MAX_BULLETS;
    private static final ForgeConfigSpec.IntValue MAX_FLIGHT_TICKS;
    private static final ForgeConfigSpec.BooleanValue SCOPE_RENDERING;
    private static final ForgeConfigSpec.IntValue SCOPE_INTERVAL, SCOPE_CANDIDATES, SCOPE_TRACE_CELLS;
    private static final ForgeConfigSpec.BooleanValue ACTIVITY;
    private static final ForgeConfigSpec.IntValue ACTIVITY_RADIUS, ACTIVITY_STABLE, ACTIVITY_RETENTION,
            ACTIVITY_CHUNKS, ACTIVITY_DEPENDENCIES, ACTIVITY_REGIONS, ACTIVITY_PLAYER_REGIONS, ACTIVITY_STARTS, ACTIVITY_TIMEOUT, ACTIVITY_QUERIES;

    private static final ForgeConfigSpec.IntValue PREDICTION_INTERVAL, PREDICTION_ENTITY_CHECKS;
    private static final ForgeConfigSpec.DoubleValue PREDICTION_MILLIS;
    public record Prediction(int intervalTicks, double maxMillisPerTick, int maxEntityChecks) {}
    public static final Prediction DEFAULT_PREDICTION = new Prediction(8, 4.0, 8192);

    public record Ballistics(boolean enabled, int maxDistance, int maxActiveBullets, int maxFlightTicks, boolean extendLifetime) {
        public Ballistics(boolean enabled, int maxDistance, int maxActiveBullets, int maxFlightTicks) {
            this(enabled, maxDistance, maxActiveBullets, maxFlightTicks, false);
        }
    }
    public record ScopeRendering(boolean enabled, int intervalTicks, int maxCandidates, int maxTraceCells) {}
    public static final ScopeRendering DEFAULT_SCOPE_RENDERING = new ScopeRendering(true, 4, 256, 65536);
    public record Activity(boolean enabled, int radius, int stableTicks, int retentionTicks, int maxFullChunks,
                           int maxDependencyChunks, int maxRegions, int startsPerSecond, int loadTimeoutTicks, int maxQueriesPerTick, int maxRegionsPerPlayer) {
        public Activity(boolean enabled, int radius, int stableTicks, int retentionTicks, int maxFullChunks,
                        int maxDependencyChunks, int maxRegions, int startsPerSecond, int loadTimeoutTicks, int maxQueriesPerTick) {
            this(enabled,radius,stableTicks,retentionTicks,maxFullChunks,maxDependencyChunks,maxRegions,startsPerSecond,loadTimeoutTicks,maxQueriesPerTick,2);
        }
        public Activity(boolean enabled, int radius, int stableTicks, int retentionTicks, int maxFullChunks,
                        int maxDependencyChunks, int maxRegions, int startsPerSecond, int loadTimeoutTicks) {
            this(enabled, radius, stableTicks, retentionTicks, maxFullChunks, maxDependencyChunks, maxRegions,
                    startsPerSecond, loadTimeoutTicks, 2);
        }
    }
    public static final Activity DEFAULT_ACTIVITY = new Activity(true, 1, 3, 60, 196, 4096, 4, 2, 100);

    public record Settings(boolean available, EligibilityPolicy policy, int maxDistance,
                           int maxTrackedEntities, boolean diagnostics, Ballistics ballistics,
                           ScopeRendering scopeRendering, Activity activity, Prediction prediction) {}
    private static final Settings UNAVAILABLE = new Settings(false, EligibilityPolicy.disabled(), 1024, 32, false,
            new Ballistics(false,1024,128,600), DEFAULT_SCOPE_RENDERING, DEFAULT_ACTIVITY, DEFAULT_PREDICTION);
    public record Targeting(double stableDegreesPerSecond, int requestIntervalTicks, int checksPerSecond,
                            int concurrentChecks, int maxQueuedChecks, int maxIndexedChunks, int newRegionIntervalTicks) {
        public Targeting(double speed, int interval, int checks, int concurrent, int queued, int indexed) {
            this(speed, interval, checks, concurrent, queued, indexed, 10);
        }
    }
    private static final ForgeConfigSpec.DoubleValue TARGET_SPEED;
    private static final ForgeConfigSpec.IntValue TARGET_INTERVAL, TARGET_NEW_REGION_INTERVAL, TARGET_CHECKS, TARGET_CONCURRENT, TARGET_QUEUE, TARGET_INDEX;
    private static final Targeting DEFAULT_TARGETING = new Targeting(10,5,512,32,4096,131072);
    private static volatile Targeting targeting=DEFAULT_TARGETING;
    public static Targeting targeting() { return targeting; }

    private static volatile Settings current = UNAVAILABLE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        HUMIDITY = new HumidityConfig(builder);
        builder.comment("Shared temporary visual section masks. Missing initial coverage defers remote drawing; simulation continues.").push("occlusion");
        OCCLUSION_ENABLED=builder.define("enabled",true);
        OCCLUSION_RADIUS=builder.comment("Target-side coverage distance, clipped to the entity's 5x5 chunk neighbourhood; reads resident terrain only.").defineInRange("radius",96,16,128);
        OCCLUSION_CELLS=builder.defineInRange("maxCellsPerPlayer",32768,256,262144);
        OCCLUSION_GLOBAL=builder.defineInRange("maxCellsPerTick",131072,256,1048576);
        OCCLUSION_SECTIONS=builder.defineInRange("maxSectionsPerPlayer",128,8,128);
        OCCLUSION_FACES=builder.comment("GPU mask face budget; exceeding it defers drawing instead of dropping cover.").defineInRange("maxFacesPerFrame",100000,4096,200000);
        OCCLUSION_REFRESH=builder.defineInRange("refreshTicks",10,1,100);
        OCCLUSION_PACKETS=builder.defineInRange("maxSectionsPerPacket",4,1,8);
        builder.pop();
        builder.comment("Delayed distant gunfire for accepted AoS shots only. Nearby TACZ audio remains native.",
                        "Distance/delay/volume use listener position at firing; no terrain occlusion or extra chunk loading.",
                        "Both clients and server need this version. Restart the world/server after edits.").push("distantGunshots");
        DISTANT_SOUND = builder.define("enabled", true);
        SOUND_SPEED = builder.comment("Blocks per second, assuming 20 server ticks per second. 343 models temperate air.")
                .defineInRange("speedOfSound", 343.0, 100, 2000);
        SOUND_MIN = builder.comment("Below this distance, keep TACZ's immediate nearby gunfire.")
                .defineInRange("minDistance", 128, 16, 16384);
        SOUND_MAX = builder.comment("Audible radius. Zero follows the configured maximum sniping distance.")
                .defineInRange("maxDistance", 0, 0, 16384);
        SOUND_VOLUME = builder.comment("Overall gain before distance attenuation. Suppressed shots use another 0.2 multiplier.")
                .defineInRange("volume", .65, 0, 1);
        builder.pop();
        builder.comment("Global crosswind for ordinary TACZ kinetic bullets. HUD still requires an eligible scoped player near a rangefinder.",
                        "Wind is shared per dimension, fixed per shot, absent in dimensions without skylight and ignored under water.",
                        "Restart the world/server after edits so clients receive the same configuration.")
                .push("wind");
        WIND_ENABLED = builder.define("enabled", true);
        WIND_WEATHER = builder.comment("Rain/thunder use their own speed distribution and interval; weather transitions may change wind immediately.")
                .define("weatherEffects", true);
        WIND_SEED = builder.comment("Salt combined with world seed, game day, time slot and weather. Reopening scopes does not reroll wind.")
                .defineInRange("seedSalt", 0L, Long.MIN_VALUE, Long.MAX_VALUE);
        String[] weatherNames = {"clear", "rain", "thunder"};
        for (int i = 0; i < 3; i++) {
            var defaults = tacz.aos.wind.WindSettings.DEFAULT.profile(i);
            builder.push(weatherNames[i]);
            WIND_PERIODS[i] = builder.comment("Ticks per time slot within each 24000-tick game day; the final slot may be shorter.")
                    .defineInRange("periodTicks", defaults.periodTicks(), 20, 24000);
            WIND_MEANS[i] = builder.comment("Rayleigh mean before truncation, in blocks/second. Must not exceed maxSpeed. Zero means calm.")
                    .defineInRange("meanSpeed", defaults.meanSpeed(), 0, 30);
            WIND_MAX[i] = builder.comment("Hard speed cap, in blocks/second; direction is uniform over 360 degrees.")
                    .defineInRange("maxSpeed", defaults.maxSpeed(), 0, 30);
            builder.pop();
        }
        builder.pop();
        builder.comment("Global TACZ projectile physics, independent of rangefinder, aiming and sniping whitelist.",
                        "Restart the world/server after editing. Native TACZ spawn packets synchronize actual bullet physics.")
                .push("gunOverrides");
        GUN_OVERRIDES_ENABLED = builder.define("enabled", true);
        GUN_OVERRIDES = builder.comment("gun_id|speed=meters/second|friction=fraction lost per tick at humidity.referenceRH.",
                        "Ordinary kinetic gravity is always 9.80665 blocks/s^2, with no gravity override or toggle.",
                        "Any TACZ gun-pack namespace is accepted. Omitted speed/friction keep their native base values.",
                        "Disabling this list does not disable fixed gravity or separately configured wind/humidity.",
                        "Speed is final muzzle speed, overriding TACZ's global speed multiplier and attachment speed changes.",
                        "Limits: speed (0,5120], friction [0,1); at most 1024 unique gun IDs.",
                        "Empty entries disables all overrides. Invalid entries reject the entire override list.")
                .defineListAllowEmpty("entries", GunPhysicsOverride.DEFAULTS, value -> value instanceof String);
        builder.pop();
        builder.push("eligibility");
        ENABLED = builder.comment("Allow qualified shots to use the enabled sniping features.")
                .define("enabled", true);
        GUNS = builder.comment("Allowed TACZ gun IDs (namespace:path). Empty means no guns are allowed.",
                        "Defaults: official TACZ Forge 1.20.1 snipers AWP, M95, M107, M700, Kar98 and Springfield 1873.",
                        "Also grants zero spread while TACZ or optional ParCool crawling, without needing a scope, ADS or rangefinder.",
                        "Prone zero spread follows eligibility.enabled; standing and non-player shots retain native spread.")
                .defineListAllowEmpty("guns", EligibilityPolicy.DEFAULT_GUNS, value -> value instanceof String);
        SCOPES = builder.comment("Scope ID whitelist. Every aiming magnification is allowed within 3 blocks of a placed rangefinder.",
                        "Entries: scope_id or scope_id|ordered zoom levels (optional server fallback projection metadata).",
                        "Legacy scope_id|ordered zoom levels|allowed zooms entries remain readable; the last field is ignored.",
                        "Client rendering uses the actual gun-pack zoom/view data. Empty means no scopes are allowed.")
                .defineListAllowEmpty("scopeRules", EligibilityPolicy.DEFAULT_SCOPES, value -> value instanceof String);
        builder.pop();
        builder.comment("Shared limits. Entity budget applies per scoped player.")
                .push("limits");
        MAX_DISTANCE = builder.defineInRange("maxDistance", 1024, 16, 16384);
        MAX_ENTITIES = builder.defineInRange("maxTrackedEntities", 32, 1, 256);
        builder.pop();
        builder.comment("Scoped kinetic bullets collide only in retained target cores. Terrain/entities outside them are ignored.",
                        "No corridor chunk loading or collision snapshots.")
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
        builder.comment("Additional entity tracking/rendering while near a rangefinder with an allowed gun/scope and aiming, at every zoom.",
                        "All entity types may be displayed; activity controls bounded server-side target loading.")
                .push("scopeRendering");
        SCOPE_RENDERING = builder.define("enabled", true);
        SCOPE_INTERVAL = builder.comment("Refresh subscriptions every N ticks; losing eligibility revokes them each tick.")
                .defineInRange("intervalTicks", 4, 2, 20);
        SCOPE_CANDIDATES = builder.comment("Maximum entity candidates inspected per refresh and player.")
                .defineInRange("maxCandidates", 256, 16, 2048);
        SCOPE_TRACE_CELLS = builder.comment("Maximum collision cells inspected per local impact prediction.")
                .defineInRange("maxTraceCells", 65536, 1024, 1048576);
        builder.pop();
        builder.comment("Temporary real simulation around known scope targets; shared server-wide budgets.",
                        "Sight line and target loading halo must consist of fully generated chunks; only generation metadata is read.")
                .push("activity");
        ACTIVITY = builder.define("enabled", true);
        ACTIVITY_RADIUS = builder.comment("Entity-ticking core radius in chunks; 1 means 3x3 core and 7x7 FULL footprint.")
                .defineInRange("radius", 1, 0, 2);
        ACTIVITY_STABLE = builder.comment("Consecutive slow-aim server ticks before requesting terrain; 20 ticks = 1 second at normal TPS.")
                .defineInRange("stableTicks", 3, 2, 100);
        ACTIVITY_RETENTION = builder.comment("Scope release grace. Accepted in-flight bullets can retain their target regions beyond this grace.")
                .defineInRange("retentionTicks", 60, 1, 200);
        ACTIVITY_CHUNKS = builder.comment("Union of reserved FULL chunk footprints, including loading regions, release grace and in-flight shots.")
                .defineInRange("maxFullChunks", 196, 25, 2048);
        ACTIVITY_DEPENDENCIES = builder.comment("Additional conservative budget for the entire ticket dependency footprint (including FULL chunks).")
                .defineInRange("maxDependencyChunks", 4096, 256, 16384);
        ACTIVITY_REGIONS = builder.comment("Server-wide region limit, including release grace and in-flight shots.")
                .defineInRange("maxRegions", 4, 1, 32);
        ACTIVITY_PLAYER_REGIONS = builder.comment("Per-player region limit, including shared regions, release grace and in-flight shots. Global budgets still apply.")
                .defineInRange("maxRegionsPerPlayer",2,1,32);
        ACTIVITY_STARTS = builder.comment("Maximum new regions admitted in any rolling 20 server ticks.")
                .defineInRange("startsPerSecond", 2, 1, 20);
        ACTIVITY_TIMEOUT = builder.comment("Release tickets if the entire core does not become entity-ticking before this deadline.")
                .defineInRange("loadTimeoutTicks", 100, 20, 600);
        ACTIVITY_QUERIES = builder.comment("Server-wide ballistic impact predictions per tick, in addition to the entity visibility budget.")
                .defineInRange("maxQueriesPerTick", 2, 1, 16);
        builder.pop();
        builder.comment("TACZ mean-trajectory prediction renews existing target regions on local impact.",
                        "Only retained target cores participate in collision. Unsupported firing scripts skip prediction.")
                .push("prediction");
        PREDICTION_INTERVAL = builder.comment("Minimum ticks between predictions per player, rounded up to a subscription refresh.")
                .defineInRange("intervalTicks", 8, 2, 20);
        PREDICTION_MILLIS = builder.comment("Soft server-wide prediction budget per tick; checked every 64 collision lookups.",
                        "Exhausted predictions do not admit targets. Actual bullet simulation has its own budgets.")
                .defineInRange("maxMillisPerTick", 4.0, 0.25, 10.0);
        PREDICTION_ENTITY_CHECKS = builder.defineInRange("maxEntityChecks", 8192, 256, 65536);
        builder.pop();
        builder.comment("Stable reticle requests and asynchronous generated-chunk metadata checks. No block snapshots.").push("targeting");
        TARGET_SPEED=builder.comment("Smoothed angular speed threshold in degrees/second; stable aim has 25% hysteresis.")
                .defineInRange("stableDegreesPerSecond",10.0,0.1,180.0);
        TARGET_INTERVAL=builder.comment("Minimum ticks between all target checks per player, including unknown paths and rejected requests.",
                        "Persists across unscoping. Only the latest current aim is checked; no old target queue.")
                .defineInRange("requestIntervalTicks",5,2,40);
        TARGET_NEW_REGION_INTERVAL=builder.comment("Minimum ticks between successfully starting new regions per player; 10 ticks = 0.5 seconds at normal TPS.",
                        "First request has no extra delay. Renewing or sharing an existing region bypasses this interval.",
                        "Persists across unscoping and dimension changes. Zero disables only this additional cooldown.")
                .defineInRange("newRegionIntervalTicks",10,0,200);
        TARGET_CHECKS=builder.defineInRange("checksPerSecond",512,16,4096);
        TARGET_CONCURRENT=builder.defineInRange("concurrentChecks",32,1,128);
        TARGET_QUEUE=builder.defineInRange("maxQueuedChecks",4096,256,16384);
        TARGET_INDEX=builder.defineInRange("maxIndexedChunks",131072,1024,1048576);
        builder.pop();
        builder.push("diagnostics");
        DIAGNOSTICS = builder.comment("Log eligibility changes, bullet parameters, takeover and termination reasons.")
                .define("enabled", false);
        builder.pop();
        SPEC = builder.build();
    }

    private ServerConfig() {}

    public static Settings current() { return current; }
    public static tacz.aos.wind.WindSettings wind() { return wind; }
    private static tacz.aos.wind.WindSettings.Profile windProfile(int weather) {
        return new tacz.aos.wind.WindSettings.Profile(WIND_PERIODS[weather].get(), WIND_MEANS[weather].get(), WIND_MAX[weather].get());
    }
    public static java.util.Map<String, GunPhysicsOverride> gunOverrides() { return gunOverrides; }

    public static void onLoad(ModConfigEvent.Loading event) { reload(event); }
    public static void onReload(ModConfigEvent.Reloading event) { reload(event); }

    public static void onUnload(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            current = UNAVAILABLE;
            distantGunshots = SILENT_GUNSHOTS;
            wind = tacz.aos.wind.WindSettings.DISABLED;
            humidity = tacz.aos.humidity.HumiditySettings.DISABLED;
            tacz.aos.humidity.HumidityField.clear();
            gunOverrides = java.util.Map.of();
            targeting = DEFAULT_TARGETING;
            occlusion = DEFAULT_OCCLUSION;
        }
    }

    private static void reload(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) return;
        try {
            humidity = HUMIDITY.read();
        } catch (IllegalArgumentException exception) {
            humidity = tacz.aos.humidity.HumiditySettings.DISABLED;
            TaczArtOfSniping.LOGGER.error("Invalid humidity config; humidity disabled: {}", exception.getMessage());
        }
        occlusion=new Occlusion(OCCLUSION_ENABLED.get(),OCCLUSION_RADIUS.get(),OCCLUSION_CELLS.get(),OCCLUSION_GLOBAL.get(),OCCLUSION_SECTIONS.get(),OCCLUSION_FACES.get(),OCCLUSION_REFRESH.get(),OCCLUSION_PACKETS.get());
        distantGunshots = new DistantGunshots(DISTANT_SOUND.get(), SOUND_SPEED.get(), SOUND_MIN.get(), SOUND_MAX.get(), SOUND_VOLUME.get());
        targeting = new Targeting(TARGET_SPEED.get(),TARGET_INTERVAL.get(),TARGET_CHECKS.get(),TARGET_CONCURRENT.get(),TARGET_QUEUE.get(),TARGET_INDEX.get(),TARGET_NEW_REGION_INTERVAL.get());
        try {
            wind = new tacz.aos.wind.WindSettings(WIND_ENABLED.get(), WIND_WEATHER.get(), WIND_SEED.get(),
                    windProfile(0), windProfile(1), windProfile(2));
        } catch (IllegalArgumentException exception) {
            wind = tacz.aos.wind.WindSettings.DISABLED;
            TaczArtOfSniping.LOGGER.error("Invalid wind config; wind disabled: {}", exception.getMessage());
        }
        try {
            gunOverrides = GUN_OVERRIDES_ENABLED.get() ? GunPhysicsOverride.parse(GUN_OVERRIDES.get()) : java.util.Map.of();
        } catch (IllegalArgumentException exception) {
            gunOverrides = java.util.Map.of();
            TaczArtOfSniping.LOGGER.error("Invalid gunOverrides config; physics overrides disabled: {}", exception.getMessage());
        }
        try {
            current = new Settings(true, EligibilityPolicy.parse(ENABLED.get(), GUNS.get(), SCOPES.get()),
                    MAX_DISTANCE.get(), MAX_ENTITIES.get(), DIAGNOSTICS.get(),
                    new Ballistics(BALLISTICS.get(), BALLISTICS_DISTANCE.get() == 0 ? MAX_DISTANCE.get() : Math.min(MAX_DISTANCE.get(), BALLISTICS_DISTANCE.get()),
                            MAX_BULLETS.get(), MAX_FLIGHT_TICKS.get(), EXTEND_LIFETIME.get()),
                    new ScopeRendering(SCOPE_RENDERING.get(), SCOPE_INTERVAL.get(), SCOPE_CANDIDATES.get(), SCOPE_TRACE_CELLS.get()),
                    new Activity(ACTIVITY.get(), ACTIVITY_RADIUS.get(), ACTIVITY_STABLE.get(), ACTIVITY_RETENTION.get(),
                            ACTIVITY_CHUNKS.get(), ACTIVITY_DEPENDENCIES.get(), ACTIVITY_REGIONS.get(), ACTIVITY_STARTS.get(),
                            ACTIVITY_TIMEOUT.get(), ACTIVITY_QUERIES.get(), ACTIVITY_PLAYER_REGIONS.get()),
                    new Prediction(PREDICTION_INTERVAL.get(), PREDICTION_MILLIS.get(), PREDICTION_ENTITY_CHECKS.get()));
        } catch (IllegalArgumentException exception) {
            current = UNAVAILABLE;
            TaczArtOfSniping.LOGGER.error("Invalid sniping eligibility config; eligibility disabled: {}", exception.getMessage());
        }
    }
}
