package tacz.longrangesniping.test;

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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector2d;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.compat.tacz.BallisticPreview;
import com.tacz.guns.item.ModernKineticGunItem;
import tacz.longrangesniping.mixin.KineticBulletAccessor;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SnipingPhysicsGameTests {
    @GameTest(template = "ballistics", batch = "global_physics_damage", timeoutTicks = 200)
    public static void nativeAndManagedShotsDamageTargetOnPredictedCurve(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        var level = helper.getLevel();
        var operator = IGunOperator.fromLivingEntity(player);
        player.setPos(player.getX(), 180, player.getZ());
        player.setOldPosAndRot(); player.setXRot(0); player.setYRot(-90);
        player.setDeltaMovement(Vec3.ZERO); player.setOnGround(true);
        helper.assertTrue(!tacz.longrangesniping.block.RangefinderAccess.nearby(player), "Native case must be outside instrument range");
        var preview = BallisticPreview.read(player).orElseThrow();
        Vec3 impact = preview.start(), motion = preview.velocity();
        for (int tick = 0; tick < 9; tick++) {
            impact = impact.add(motion);
            motion = motion.scale(1f - preview.friction()).add(0, -preview.gravity(), 0);
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
                    fields.taczlrs$setGravity(data.getBulletData().getGravity());
                }
                var scheduler = new tacz.longrangesniping.ballistics.LocalBallistics(level);
                if (mode == 2) {
                    TestRangefinders.placeNear(player);
                    LocalBallisticsGameTests.accept(helper, scheduler, bullet,
                            new tacz.longrangesniping.config.ServerConfig.Ballistics(true, 1024, 16, 600));
                }
                for (int tick = 0; tick < 11 && !bullet.isRemoved(); tick++) {
                    if (mode == 2) scheduler.tick(); else level.tickNonPassenger(bullet);
                }
                helper.assertTrue(mode == 0 ? target.getHealth() == 1000 : target.getHealth() < 1000,
                        "Actual damage disagrees with predicted curve, mode=" + mode + " health=" + target.getHealth());
                bullet.discard(); scheduler.tick();
            }
        } finally { target.discard(); }
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "sniping_physics")
    public static void allLaunchRoutesUseGlobalPhysicsWithoutChangingSharedData(GameTestHelper helper) {
        var old = tacz.longrangesniping.config.ServerConfig.current();
        // Run against this release's default whitelist even if the isolated test world has an older config.
        LocalBallisticsGameTests.setSettings(new tacz.longrangesniping.config.ServerConfig.Settings(old.available(),
                tacz.longrangesniping.config.EligibilityPolicy.parse(true,
                        tacz.longrangesniping.config.EligibilityPolicy.DEFAULT_GUNS,
                        tacz.longrangesniping.config.EligibilityPolicy.DEFAULT_SCOPES),
                old.maxDistance(), old.maxTrackedEntities(), old.snapshotMemoryMiB(), old.diagnostics(),
                old.ballistics(), old.snapshots(), old.scopeRendering(), old.activity(), old.persistence(), old.prediction()));
        try {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        var origin = player.position();
        var operator = IGunOperator.fromLivingEntity(player);
        String[] ids = {"ai_awp", "m95", "m107", "m700", "kar98", "springfield1873"};
        float[] speeds = {45.25f, 42.9f, 42.9f, 41.148f, 39f, -1};
        float[] drags = {143f/6000, 86f/6000, 86f/6000, .030666667f, .0275f, -1};
        for (int i = 0; i < ids.length; i++) {
            var stack = GunItemBuilder.create().setId(ResourceLocation.parse("tacz:" + ids[i]))
                    .putAttachment(AttachmentType.SCOPE, ResourceLocation.parse(i == 5 ? "tacz:scope_1873_6x"
                            : i == 4 ? "tacz:scope_98k" : "tacz:scope_mk5hd"))
                    .build(helper.getLevel().registryAccess());
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
                gun.setAttachmentTag(stack, AttachmentType.SCOPE, tag);
                var preview = BallisticPreview.read(player).orElseThrow();
                var bullet = new EntityKineticBullet(helper.getLevel(), player, stack, data.getAmmoId(), gun.getGunId(stack),
                        false, data, index.getBulletData());
                // Exercise production dispatch: default guns select vanilla; Lua spread selects TACZ's overload.
                ((ModernKineticGunItem) gun).doBulletSpread(operator.getDataHolder(), stack, player, bullet,
                        1, nativeSpeed, 0, player.getXRot(), player.getYRot());
                var fields = (KineticBulletAccessor) bullet;
                boolean changed = i < 5;
                double speed = changed ? speeds[i] : nativeSpeed;
                helper.assertTrue(Math.abs(bullet.getDeltaMovement().subtract(player.getKnownMovement()).length() - speed) < 1e-4,
                        "Wrong launch speed: " + ids[i] + " state=" + state);
                helper.assertTrue(Math.abs(fields.taczlrs$getGravity() - (changed ? 9.80665f / 400f : originalGravity)) < 1e-7
                                && Math.abs(fields.taczlrs$getFriction() - (changed ? drags[i] : originalDrag)) < 1e-7,
                        "Wrong gravity/drag: " + ids[i] + " state=" + state);
                helper.assertTrue(preview.velocity().distanceTo(bullet.getDeltaMovement()) < 1e-4
                                && preview.gravity() == fields.taczlrs$getGravity() && preview.friction() == fields.taczlrs$getFriction(),
                        "Actual TACZ launch disagrees with prediction: " + ids[i] + " state=" + state);
                var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
                try {
                    bullet.writeSpawnData(buffer);
                    var received = new EntityKineticBullet(EntityKineticBullet.TYPE, helper.getLevel());
                    received.readSpawnData(buffer);
                    var receivedFields = (KineticBulletAccessor) received;
                    helper.assertTrue(received.getDeltaMovement().equals(bullet.getDeltaMovement())
                                    && receivedFields.taczlrs$getGravity() == fields.taczlrs$getGravity()
                                    && receivedFields.taczlrs$getFriction() == fields.taczlrs$getFriction(),
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
