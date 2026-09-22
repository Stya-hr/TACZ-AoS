package tacz.aos.test;

import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector2d;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.compat.tacz.BallisticPreview;
import com.tacz.guns.item.ModernKineticGunItem;
import tacz.aos.mixin.KineticBulletAccessor;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SnipingPhysicsGameTests {
    @GameTest(setupTicks = 20, template = "ballistics", batch = "crawlondemand_spread")
    public static void optionalCrawlOnDemandControlsBothLaunchRoutes(GameTestHelper helper) throws Exception {
        var old = tacz.aos.config.ServerConfig.current();
        var player = new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "crawl-demand-test"));
        var fixture = new LocalBallisticsGameTests.Fixture(helper, player);
        player.setDeltaMovement(Vec3.ZERO); player.setOnGround(true);
        var operator = IGunOperator.fromLivingEntity(player);
        operator.crawl(false); operator.aim(false);
        // A visual low pose must never count by itself, even when the optional mod is installed.
        player.setPose(net.minecraft.world.entity.Pose.SWIMMING);
        helper.assertTrue(!tacz.aos.compat.crawlondemand.CrawlOnDemandCompat.isCrawling(player),
                "Swimming pose mistaken for Crawl on Demand input");
        if (!net.minecraftforge.fml.ModList.get().isLoaded("crawlondemand")) {
            helper.succeed(); return;
        }
        var manager = Class.forName("me.thecraftycoder.minecraft.crawlondemand.platform.CrawlStateManager");
        var setter = manager.getMethod("setCrawling", net.minecraft.world.entity.player.Player.class, boolean.class);
        var packet = Class.forName("me.thecraftycoder.minecraft.crawlondemand.network.CrawlStatePacket");
        var handle = packet.getMethod("handle", net.minecraft.server.level.ServerPlayer.class);
        var gun = (ModernKineticGunItem) IGun.getIGunOrNull(fixture.stack);
        var data = TimelessAPI.getCommonGunIndex(gun.getGunId(fixture.stack)).orElseThrow().getGunData();
        try {
            for (int state = 0; state < 6; state++) {
                boolean active = state == 1 || state == 2 || state == 4 || state == 5;
                // Use the real server packet application, not a flag owned by this mod.
                handle.invoke(packet.getConstructor(boolean.class).newInstance(active), player);
                var policy = tacz.aos.config.EligibilityPolicy.parse(state != 5,
                        state == 2 ? java.util.List.of() : java.util.List.of("tacz:ai_awp"), java.util.List.of());
                LocalBallisticsGameTests.setSettings(new tacz.aos.config.ServerConfig.Settings(true, policy,
                        old.maxDistance(), old.maxTrackedEntities(), old.diagnostics(), old.ballistics(),
                        old.scopeRendering(), old.activity(), old.prediction()));
                helper.assertTrue(!operator.getDataHolder().isCrawling, "Test accidentally enabled TACZ crawl");
                helper.assertTrue(tacz.aos.compat.crawlondemand.CrawlOnDemandCompat.isCrawling(player) == active,
                        "Crawl on Demand server state not read");
                for (int route = 0; route < 2; route++) {
                    var bullet = new EntityKineticBullet(helper.getLevel(), player, fixture.stack, data.getAmmoId(),
                            gun.getGunId(fixture.stack), false, data, data.getBulletData());
                    bullet.getRandom().setSeed(12345);
                    if (route == 0) gun.doBulletSpread(operator.getDataHolder(), fixture.stack, player, bullet, 1, 20, 2, 0, 0);
                    else bullet.shootFromRotation(player, 0, 0, 0, 20, new Vector2d(.2, -.1));
                    double lateral = Math.hypot(bullet.getDeltaMovement().x, bullet.getDeltaMovement().y);
                    helper.assertTrue(active && state != 2 && state != 5 ? lateral < 1e-8 : lateral > .01,
                            "Crawl on Demand spread mismatch: state=" + state + " route=" + route + " lateral=" + lateral);
                    bullet.discard();
                }
            }
            TaczArtOfSniping.LOGGER.info("CRAWL_ON_DEMAND_SERVER PASSED: packet state, both launch routes, whitelist, disabled policy, start/stop");
            helper.succeed();
        } finally {
            LocalBallisticsGameTests.setSettings(old);
            setter.invoke(null, player, false);
        }
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "parcool_spread")
    public static void optionalParCoolActionControlsActualBulletSpread(GameTestHelper helper) throws Exception {
        var old = tacz.aos.config.ServerConfig.current();
        // GameTestHelper.makeMockPlayer() reports isLocalPlayer=true even on the server.
        var serverPlayer = new net.minecraftforge.common.util.FakePlayer(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "parcool-spread-test"));
        var fixture = new LocalBallisticsGameTests.Fixture(helper, serverPlayer);
        var player = fixture.player;
        player.setPos(player.position().add(0, 40, 0)); player.setOldPosAndRot();
        player.setDeltaMovement(Vec3.ZERO); player.setOnGround(true);
        var operator = IGunOperator.fromLivingEntity(player);
        operator.crawl(false); operator.aim(false);
        if (!net.minecraftforge.fml.ModList.get().isLoaded("parcool")) {
            helper.assertTrue(!tacz.aos.compat.parcool.ParCoolCompat.isCrawling(player), "Absent optional mod reported crawling");
            helper.succeed(); return;
        }
        Class<?> abilityType;
        boolean modern;
        try { abilityType = Class.forName("com.alrex.parcool.common.Parkourability"); modern = true; }
        catch (ClassNotFoundException e) {
            abilityType = Class.forName("com.alrex.parcool.common.capability.Parkourability"); modern = false;
        }
        Object ability = abilityType.getMethod("get", net.minecraft.world.entity.player.Player.class).invoke(null, player);
        helper.assertTrue(ability != null, "ParCool player attachment missing");
        Object crawl;
        if (modern) {
            Object key = Class.forName("com.alrex.parcool.common.action.ParCoolActions").getField("CRAWL").get(null);
            crawl = abilityType.getMethod("get", Class.forName("com.alrex.parcool.api.action.ActionEntry")).invoke(ability, key);
        } else {
            crawl = abilityType.getMethod("get", Class.class).invoke(ability, Class.forName("com.alrex.parcool.common.action.impl.Crawl"));
        }
        var gun = (ModernKineticGunItem) IGun.getIGunOrNull(fixture.stack);
        var data = TimelessAPI.getCommonGunIndex(gun.getGunId(fixture.stack)).orElseThrow().getGunData();
        try {
            for (int state = 0; state < 5; state++) {
                // Start/stop through the same action methods used by ParCool's server packet handler.
                boolean active = state == 1 || state == 2 || state == 4;
                if (active) {
                    if (modern) crawl.getClass().getMethod("start").invoke(crawl);
                    else crawl.getClass().getMethod("start", net.minecraft.world.entity.player.Player.class, abilityType,
                            java.nio.ByteBuffer.class, Class.forName("com.alrex.parcool.common.capability.IStamina"))
                            .invoke(crawl, player, ability, java.nio.ByteBuffer.allocate(0), Class.forName("com.alrex.parcool.common.capability.IStamina")
                                    .getMethod("get", net.minecraft.world.entity.player.Player.class).invoke(null, player));
                } else {
                    if (modern) crawl.getClass().getMethod("finish").invoke(crawl);
                    else crawl.getClass().getMethod("finish", net.minecraft.world.entity.player.Player.class).invoke(crawl, player);
                }
                var policy = tacz.aos.config.EligibilityPolicy.parse(true,
                        state == 2 ? java.util.List.of() : java.util.List.of("tacz:ai_awp"), java.util.List.of());
                LocalBallisticsGameTests.setSettings(new tacz.aos.config.ServerConfig.Settings(true, policy,
                        old.maxDistance(), old.maxTrackedEntities(), old.diagnostics(), old.ballistics(), old.scopeRendering(), old.activity(), old.prediction()));
                helper.assertTrue(!operator.getDataHolder().isCrawling, "ParCool test accidentally enabled TACZ crawl");
                helper.assertTrue(tacz.aos.compat.parcool.ParCoolCompat.isCrawling(player) == active, "ParCool action transition was not read");
                for (int route = 0; route < 2; route++) {
                    var bullet = new EntityKineticBullet(helper.getLevel(), player, fixture.stack, data.getAmmoId(),
                            gun.getGunId(fixture.stack), false, data, data.getBulletData());
                    bullet.getRandom().setSeed(12345);
                    if (route == 0) gun.doBulletSpread(operator.getDataHolder(), fixture.stack, player, bullet, 1, 20, 2, 0, 0);
                    else bullet.shootFromRotation(player, 0, 0, 0, 20, new Vector2d(.2, -.1));
                    double lateral = Math.hypot(bullet.getDeltaMovement().x, bullet.getDeltaMovement().y);
                    helper.assertTrue(active && state != 2 ? lateral < 1e-8 : lateral > .01,
                            "ParCool actual spread mismatch: state=" + state + " route=" + route + " lateral=" + lateral);
                    bullet.discard();
                }
            }
            tacz.aos.TaczArtOfSniping.LOGGER.info("ParCool {} crawl start/stop, whitelist and both actual launch routes passed", modern ? "4.x" : "3.x");
            helper.succeed();
        } finally {
            LocalBallisticsGameTests.setSettings(old);
            if (modern) crawl.getClass().getMethod("finish").invoke(crawl);
            else crawl.getClass().getMethod("finish", net.minecraft.world.entity.player.Player.class).invoke(crawl, player);
        }
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "prone_spread")
    public static void proneWhitelistClearsBothLaunchRoutesAndRestoresNativeSpread(GameTestHelper helper) {
        var old = tacz.aos.config.ServerConfig.current();
        var player = helper.makeMockPlayer();
        player.setPos(helper.absoluteVec(new Vec3(4, 10, 4)));
        player.setOldPosAndRot(); player.setOnGround(true);
        player.setDeltaMovement(new Vec3(.13, 0, .21));
        // Bare gun, no rangefinder and no ADS: only the gun whitelist and TACZ crawl flag apply.
        var stack = GunItemBuilder.create().setId(new ResourceLocation("tacz:ai_awp"))
                .build();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var gun = (ModernKineticGunItem) IGun.getIGunOrNull(stack);
        var data = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow().getGunData();
        var operator = IGunOperator.fromLivingEntity(player);
        operator.initialData(); operator.aim(false);
        var cache = new AttachmentCacheProperty(); cache.eval(stack, data); operator.updateCacheProperty(cache);
        var npc = net.minecraft.world.entity.EntityType.ZOMBIE.create(helper.getLevel());
        npc.setPos(player.position()); npc.setOnGround(true); npc.setDeltaMovement(player.getDeltaMovement());
        var npcOperator = IGunOperator.fromLivingEntity(npc);
        npcOperator.initialData(); npcOperator.crawl(true);
        npc.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
        var npcCache = new AttachmentCacheProperty(); npcCache.eval(stack, data); npcOperator.updateCacheProperty(npcCache);
        try {
            for (int state = 0; state < 8; state++) {
                // Allowed prone, standing, swimming pose only, excluded, disabled, NPC, unavailable, prone again.
                var policy = tacz.aos.config.EligibilityPolicy.parse(state != 4,
                        state == 3 ? java.util.List.of("other_pack:sniper") : java.util.List.of("tacz:ai_awp"),
                        java.util.List.of());
                LocalBallisticsGameTests.setSettings(new tacz.aos.config.ServerConfig.Settings(state != 6, policy,
                        old.maxDistance(), old.maxTrackedEntities(), old.diagnostics(), old.ballistics(),
                        old.scopeRendering(), old.activity(), old.prediction()));
                operator.crawl(state != 1 && state != 2);
                player.setPose(state == 2 ? net.minecraft.world.entity.Pose.SWIMMING : net.minecraft.world.entity.Pose.STANDING);
                net.minecraft.world.entity.LivingEntity shooter = state == 5 ? npc : player;
                boolean shouldClear = state == 0 || state == 7;
                for (int route = 0; route < 2; route++) {
                    var actual = new EntityKineticBullet(helper.getLevel(), shooter, stack, data.getAmmoId(),
                            gun.getGunId(stack), false, data, data.getBulletData());
                    var reference = new EntityKineticBullet(helper.getLevel(), shooter, stack, data.getAmmoId(),
                            gun.getGunId(stack), false, data, data.getBulletData());
                    actual.getRandom().setSeed(12345); reference.getRandom().setSeed(12345);
                    var scriptSpread = new Vector2d(.2, -.1);
                    if (route == 0) {
                        gun.doBulletSpread(IGunOperator.fromLivingEntity(shooter).getDataHolder(), stack, shooter,
                                actual, 1, 20, 2, -8, -90);
                        reference.shootFromRotation(shooter, -8, -90, 0, 20, 0f);
                    } else {
                        actual.shootFromRotation(shooter, -8, -90, 0, 20, scriptSpread);
                        reference.shootFromRotation(shooter, -8, -90, 0, 20, new Vector2d());
                    }
                    double difference = actual.getDeltaMovement().distanceTo(reference.getDeltaMovement());
                    helper.assertTrue(shouldClear ? difference < 1e-8 : difference > .01,
                            "Wrong prone spread: state=" + state + " route=" + route + " delta=" + difference);
                    helper.assertTrue(scriptSpread.x == .2 && scriptSpread.y == -.1, "Mutated script-owned spread vector");
                    var actualFields = (KineticBulletAccessor) actual;
                    var referenceFields = (KineticBulletAccessor) reference;
                    helper.assertTrue(actualFields.tacz_aos$getGravity() == referenceFields.tacz_aos$getGravity()
                                    && actualFields.tacz_aos$getFriction() == referenceFields.tacz_aos$getFriction(),
                            "Prone spread changed gravity/drag");
                    if (shouldClear) {
                        double speed = tacz.aos.compat.tacz.GunPhysics.launchSpeed(reference, 20);
                        helper.assertTrue(Math.abs(actual.getDeltaMovement().subtract(shooter.getDeltaMovement()).length() - speed) < 1e-5,
                                "Prone spread changed speed or inherited shooter movement");
                    }
                    actual.discard(); reference.discard();
                }
            }
            helper.succeed();
        } finally {
            operator.crawl(false); npc.discard();
            LocalBallisticsGameTests.setSettings(old);
        }
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "global_physics_damage", timeoutTicks = 200)
    public static void nativeAndManagedShotsDamageTargetOnPredictedCurve(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        var level = helper.getLevel();
        var operator = IGunOperator.fromLivingEntity(player);
        player.setPos(player.getX(), 180, player.getZ());
        player.setOldPosAndRot(); player.setXRot(0); player.setYRot(-90);
        player.setDeltaMovement(Vec3.ZERO); player.setOnGround(true);
        helper.assertTrue(!tacz.aos.block.RangefinderAccess.nearby(player), "Native case must be outside instrument range");
        var preview = BallisticPreview.read(player).orElseThrow();
        Vec3 impact = preview.start(), motion = preview.velocity();
        for (int tick = 0; tick < 9; tick++) {
            impact = impact.add(motion);
            motion = tacz.aos.wind.WindPhysics.afterDrag(
                    motion.scale(1f - preview.friction()).add(0, -preview.gravity(), 0), preview.crosswind(), preview.friction(), false);
        }
        impact = impact.add(motion.scale(.5));
        // Test setup deliberately loads an empty high-altitude corridor, never production loading code.
        for (int cx = player.blockPosition().getX() >> 4; cx <= ((int) impact.x >> 4) + 1; cx++) {
            for (int cz = (player.blockPosition().getZ() >> 4) - 1; cz <= (player.blockPosition().getZ() >> 4) + 1; cz++) level.getChunk(cx, cz);
        }
        var target = net.minecraft.world.entity.EntityType.ZOMBIE.create(level);
        target.setPos(impact.add(0, -.9, 0)); target.setOldPosAndRot(); target.setNoAi(true); target.setNoGravity(true);
        target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
        target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR).setBaseValue(0);
        helper.assertTrue(level.addFreshEntity(target), "Could not insert target");
        helper.assertTrue(level.getEntities(player, target.getBoundingBox()).contains(target), "Target chunk is not entity-accessible yet");
        var gun = (ModernKineticGunItem) IGun.getIGunOrNull(fixture.stack);
        var data = TimelessAPI.getCommonGunIndex(gun.getGunId(fixture.stack)).orElseThrow().getGunData();
        float nativeSpeed = (float) (operator.getCacheProperty().<Float>getCache(GunProperties.AMMO_SPEED)
                * AmmoConfig.GLOBAL_BULLET_SPEED_MODIFIER.get()) / 20f;
        try {
            for (int mode = 0; mode < 3; mode++) {
                target.setHealth(1000); target.invulnerableTime = 0;
                var bullet = new EntityKineticBullet(level, player, fixture.stack, data.getAmmoId(), gun.getGunId(fixture.stack),
                        false, data, data.getBulletData());
                gun.doBulletSpread(operator.getDataHolder(), fixture.stack, player, bullet, 1, nativeSpeed, 0, 0, -90);
                var fields = (KineticBulletAccessor) bullet;
                if (mode == 0) {
                    // Negative control: original gravity at the same new muzzle speed must miss this target.
                    fields.tacz_aos$setGravity(data.getBulletData().getGravity());
                }
                var scheduler = new tacz.aos.ballistics.LocalBallistics(level);
                for (int tick = 0; tick < 11 && !bullet.isRemoved(); tick++) {
                    if (mode == 2) {
                        bullet.setOldPosAndRot();bullet.tickCount++;
                        tacz.aos.ballistics.TargetBallistics.tick(level,bullet,1024,
                            new tacz.aos.ballistics.TargetCollisionSource(level,java.util.List.of(target.getBoundingBox().inflate(8))));
                    } else level.tickNonPassenger(bullet);
                }
                helper.assertTrue(mode == 0 ? target.getHealth() == 1000 : target.getHealth() < 1000,
                        "Actual damage disagrees with predicted curve, mode=" + mode + " health=" + target.getHealth());
                bullet.discard(); scheduler.tick();
            }
        } finally { target.discard(); }
        helper.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "sniping_physics")
    public static void allLaunchRoutesUseGlobalPhysicsWithoutChangingSharedData(GameTestHelper helper) {
        var old = tacz.aos.config.ServerConfig.current();
        // Run against this release's default whitelist even if the isolated test world has an older config.
        LocalBallisticsGameTests.setSettings(new tacz.aos.config.ServerConfig.Settings(old.available(),
                tacz.aos.config.EligibilityPolicy.parse(true,
                        tacz.aos.config.EligibilityPolicy.DEFAULT_GUNS,
                        tacz.aos.config.EligibilityPolicy.DEFAULT_SCOPES),
                old.maxDistance(), old.maxTrackedEntities(), old.diagnostics(),
                old.ballistics(), old.scopeRendering(), old.activity(), old.prediction()));
        try {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        var origin = player.position();
        var operator = IGunOperator.fromLivingEntity(player);
        String[] ids = {"ai_awp", "m95", "m107", "m700", "kar98", "springfield1873"};
        float[] speeds = {45.25f, 42.9f, 42.9f, 41.148f, 39f, -1};
        float[] drags = {143f/6000, 86f/6000, 86f/6000, .030666667f, .0275f, -1};
        for (int i = 0; i < ids.length; i++) {
            var stack = GunItemBuilder.create().setId(new ResourceLocation("tacz:" + ids[i]))
                    .putAttachment(AttachmentType.SCOPE, new ResourceLocation(i == 5 ? "tacz:scope_1873_6x"
                            : i == 4 ? "tacz:scope_98k" : "tacz:scope_mk5hd"))
                    .build();
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var gun = IGun.getIGunOrNull(stack);
            var index = TimelessAPI.getCommonGunIndex(gun.getGunId(stack)).orElseThrow();
            var data = index.getGunData();
            var cache = new AttachmentCacheProperty(); cache.eval(stack, data); operator.updateCacheProperty(cache);
            float cachedSpeed = cache.getCache(GunProperties.AMMO_SPEED);
            float nativeSpeed = (float) (cachedSpeed * AmmoConfig.GLOBAL_BULLET_SPEED_MODIFIER.get()) / 20f;
            float originalGravity = index.getBulletData().getGravity(), originalDrag = index.getBulletData().getFriction();
            for (int state = 0; state < 5; state++) {
                // High/low/backup magnification, still aimed outside range, then nearby with aim released.
                player.setPos(state == 3 ? origin.add(-8, 0, 0) : origin);
                player.setOldPosAndRot(); player.setYRot(-90); player.setXRot(-8); player.setOnGround(false);
                player.setDeltaMovement(new Vec3(.13, -.07, .21));
                operator.aim(state != 4);
                helper.assertTrue(gun.getAttachmentTag(stack, AttachmentType.SCOPE) != null,
                        "Fixture scope is incompatible with " + ids[i]);
                var tag = gun.getAttachmentTag(stack, AttachmentType.SCOPE).copy();
                tag.putInt("ZoomNumber", i < 4 && state < 3 ? state : 0);
                gun.getAttachmentTag(stack, AttachmentType.SCOPE).merge(tag);
                var preview = BallisticPreview.read(player).orElseThrow();
                var bullet = new EntityKineticBullet(helper.getLevel(), player, stack, data.getAmmoId(), gun.getGunId(stack),
                        false, data, index.getBulletData());
                // Exercise production dispatch: default guns select vanilla; Lua spread selects TACZ's overload.
                ((ModernKineticGunItem) gun).doBulletSpread(operator.getDataHolder(), stack, player, bullet,
                        1, nativeSpeed, 0, player.getXRot(), player.getYRot());
                var fields = (KineticBulletAccessor) bullet;
                var actualWind = ((tacz.aos.wind.WindBulletAccess) bullet).tacz_aos$wind();
                helper.assertTrue(Math.abs(actualWind.x() - preview.crosswind().x()) < 1e-8
                                && Math.abs(actualWind.z() - preview.crosswind().z()) < 1e-8,
                        "Default launch wind disagrees with preview");
                boolean changed = i < 5;
                double speed = changed ? speeds[i] : nativeSpeed;
                helper.assertTrue(Math.abs(bullet.getDeltaMovement().subtract(player.getDeltaMovement()).length() - speed) < 1e-4,
                        "Wrong launch speed: " + ids[i] + " state=" + state);
                float expectedDrag = tacz.aos.humidity.HumidityModel.friction(changed ? drags[i] : originalDrag,
                        tacz.aos.humidity.HumidityField.current(helper.getLevel()).densityRatio());
                helper.assertTrue(Math.abs(fields.tacz_aos$getGravity() - 9.80665f / 400f) < 1e-7
                                && Math.abs(fields.tacz_aos$getFriction() - expectedDrag) < 1e-7,
                        "Wrong gravity/drag: " + ids[i] + " state=" + state);
                helper.assertTrue(preview.velocity().distanceTo(bullet.getDeltaMovement()) < 1e-4
                                && preview.gravity() == fields.tacz_aos$getGravity() && preview.friction() == fields.tacz_aos$getFriction(),
                        "Actual TACZ launch disagrees with prediction: " + ids[i] + " state=" + state);
                var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    bullet.writeSpawnData(buffer);
                    var received = new EntityKineticBullet(EntityKineticBullet.TYPE, helper.getLevel());
                    received.readSpawnData(buffer);
                    var receivedFields = (KineticBulletAccessor) received;
                    helper.assertTrue(received.getDeltaMovement().equals(bullet.getDeltaMovement())
                                    && receivedFields.tacz_aos$getGravity() == fields.tacz_aos$getGravity()
                                    && receivedFields.tacz_aos$getFriction() == fields.tacz_aos$getFriction()
                                    && ((tacz.aos.wind.WindBulletAccess) received).tacz_aos$wind().equals(actualWind),
                            "Native spawn packet lost overridden projectile physics");
                } finally { buffer.release(); }
                helper.assertTrue(cache.<Float>getCache(GunProperties.AMMO_SPEED) == cachedSpeed
                                && index.getBulletData().getGravity() == originalGravity && index.getBulletData().getFriction() == originalDrag,
                        "Shared TACZ data was changed");
                // A second launch with spread must keep the same normalized speed and retain the spread direction.
                bullet.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, nativeSpeed, new Vector2d(.2, -.1));
                helper.assertTrue(Math.abs(bullet.getDeltaMovement().subtract(player.getDeltaMovement()).length() - speed) < 1e-4
                                && bullet.getDeltaMovement().distanceTo(preview.velocity()) > .01,
                        "Profile removed spread or rescaled shooter motion");
            }
        }
        helper.succeed();
        } finally { LocalBallisticsGameTests.setSettings(old); }
    }
}
