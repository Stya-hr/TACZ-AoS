package tacz.aos.test;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.compat.tacz.BulletParameters;
import tacz.aos.compat.tacz.TaczBridge;
import tacz.aos.config.ServerConfig;
import tacz.aos.eligibility.Eligibility.Reason;
import tacz.aos.mixin.KineticBulletAccessor;

/** Real dedicated-server tests; this source set is never included in the release JAR. */
@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TaczBridgeGameTests {
    @GameTest(template = "ballistics", batch = "tacz_bridge")
    public static void readsServerConfigAndTaczEquipment(GameTestHelper helper) {
        helper.assertTrue(ServerConfig.current().available(), "Server config was not loaded");
        var ballistics = ServerConfig.current().ballistics();
        helper.assertTrue(ballistics.enabled() && ballistics.extendLifetime()
                        && ballistics.maxDistance() == ServerConfig.current().maxDistance(),
                "Formal ballistic defaults must enable takeover, extend lifetime and follow global distance");
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(TaczBridge.evaluate(player).reason() == Reason.NOT_A_GUN, "Empty hand qualified");
        var stack = GunItemBuilder.create().setId(id("tacz:ai_awp"))
                .putAttachment(AttachmentType.SCOPE, id("tacz:scope_mk5hd"))
                .build(helper.getLevel().registryAccess());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var gun = IGun.getIGunOrNull(stack);
        helper.assertTrue(gun != null, "Gun builder failed");
        var tag = gun.getAttachmentTag(stack, AttachmentType.SCOPE).copy();
        tag.putInt("ZoomNumber", 1);
        gun.setAttachmentTag(stack, AttachmentType.SCOPE, tag);
        var unscoped = TaczBridge.evaluate(player);
        helper.assertTrue(unscoped.reason() == Reason.NOT_AIMING && unscoped.zoom() == 25,
                "Server must resolve 25x but deny hipfire: " + unscoped);
        var operator = IGunOperator.fromLivingEntity(player);
        operator.aim(true);
        player.setPos(helper.absoluteVec(new Vec3(4, 5, 4)));
        helper.assertTrue(TaczBridge.evaluate(player).reason() == Reason.NO_NEARBY_RANGEFINDER, "Missing device qualified");
        TestRangefinders.placeNear(player);
        var scoped = TaczBridge.evaluate(player);
        helper.assertTrue(scoped.eligible(), "Aimed 25x should qualify: " + scoped);
        operator.aim(false);
        helper.assertTrue(TaczBridge.evaluate(player).reason() == Reason.NOT_AIMING, "Release must revoke eligibility in the same tick");
        operator.aim(true);
        tag = gun.getAttachmentTag(stack, AttachmentType.SCOPE).copy();
        tag.putInt("ZoomNumber", 2);
        gun.setAttachmentTag(stack, AttachmentType.SCOPE, tag);
        helper.assertTrue(TaczBridge.evaluate(player).eligible(), "Low power must remain eligible near a device");
        operator.aim(false);
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "tacz_bridge")
    public static void capturesInitializedBulletWithoutChangingIt(GameTestHelper helper) {
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(helper.absoluteVec(new Vec3(4, 5, 4)));
        TestRangefinders.placeNear(player);
        var gunId = id("tacz:ai_awp");
        var stack = GunItemBuilder.create().setId(gunId)
                .putAttachment(AttachmentType.SCOPE, id("tacz:scope_mk5hd"))
                .build(helper.getLevel().registryAccess());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var data = TimelessAPI.getCommonGunIndex(gunId).orElseThrow().getGunData();
        var operator = IGunOperator.fromLivingEntity(player);
        operator.initialData();
        var cache = new AttachmentCacheProperty();
        cache.eval(stack, data);
        operator.updateCacheProperty(cache);
        var bullet = new EntityKineticBullet(helper.getLevel(), player, stack, data.getAmmoId(), gunId,
                false, data, data.getBulletData());
        bullet.setDeltaMovement(new Vec3(2.5, -0.2, 0.75));
        var before = bullet.getDeltaMovement();
        helper.assertTrue(bullet instanceof KineticBulletAccessor, "Accessor mixin not applied");
        var fields = (KineticBulletAccessor) bullet;
        var snapshot = BulletParameters.capture(bullet);
        helper.assertTrue(snapshot.velocity().equals(before) && bullet.getDeltaMovement().equals(before), "Velocity changed");
        helper.assertTrue(snapshot.lifetimeTicks() == fields.tacz_aos$getLife() && snapshot.lifetimeTicks() > 0, "Lifetime not captured");
        helper.assertTrue(snapshot.gravity() == fields.tacz_aos$getGravity(), "Gravity not captured");
        helper.assertTrue(snapshot.friction() == fields.tacz_aos$getFriction(), "Friction not captured");
        helper.assertTrue(!bullet.isRemoved(), "Observation removed original bullet");
        helper.assertTrue(TaczBridge.inspectNewBullet(bullet).isEmpty(), "Unscoped bullet should not qualify");
        var gun = IGun.getIGunOrNull(stack);
        var tag = gun.getAttachmentTag(stack, AttachmentType.SCOPE).copy();
        tag.putInt("ZoomNumber", 1);
        gun.setAttachmentTag(stack, AttachmentType.SCOPE, tag);
        operator.aim(true);
        var context = TaczBridge.inspectNewBullet(bullet).orElseThrow();
        helper.assertTrue(context.shooterId().equals(player.getUUID()) && context.eligibilityAtFire().zoom() == 25,
                "Eligible shot lost its shooter or zoom");
        operator.aim(false);
        helper.assertTrue(TaczBridge.inspectNewBullet(bullet).isEmpty(), "Release must prevent a new shot context");
        helper.assertTrue(context.eligibilityAtFire().eligible(), "Release mutated an earlier shot snapshot");
        helper.assertTrue(!bullet.isRemoved() && bullet.getDeltaMovement().equals(before), "Shot inspection changed the bullet");
        helper.succeed();
    }

    private static ResourceLocation id(String id) { return ResourceLocation.parse(id); }
}
