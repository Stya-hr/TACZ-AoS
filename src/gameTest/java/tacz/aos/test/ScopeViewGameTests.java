package tacz.aos.test;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.ScopeViewPayload;
import tacz.aos.scoping.ScopeCone;
import tacz.aos.scoping.ScopeSubscriptions;
import tacz.aos.scoping.ScopeViews;

import java.util.List;
import java.util.UUID;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScopeViewGameTests {
    @GameTest(setupTicks = 20, template = "ballistics", batch = "scope_views")
    public static void lensMarginRetainsTargetsWithoutCorridorQueriesOrExceedingCountLimit(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "scope-margin"));
        new LocalBallisticsGameTests.Fixture(helper, player);
        player.setPos(helper.absoluteVec(new Vec3(4, 4, 4)));
        player.setYRot(-90); player.setYHeadRot(-90); player.setXRot(0);
        var original = ServerConfig.current();
        var target = EntityType.ITEM_DISPLAY.create(level);
        var center = EntityType.ITEM_DISPLAY.create(level);
        double width = 10 * Math.tan(0.1);
        target.setPos(player.getX() + 10, player.getEyeY(), player.getZ() + width * 1.05);
        level.addFreshEntity(target);
        var subscriptions = ScopeSubscriptions.INSTANCE;
        int loaded = level.getChunkSource().getLoadedChunksCount();
        try {
            LocalBallisticsGameTests.setSettings(new ServerConfig.Settings(original.available(), original.policy(), 20, 1, original.diagnostics(), original.ballistics(), original.scopeRendering(), original.activity(), ServerConfig.DEFAULT_PREDICTION));
            ScopeViews.INSTANCE.receive(player, new ScopeViewPayload(level.dimension().location(), 25, 0.1f));
            helper.assertTrue(subscriptions.select(player, 25).contains(target), "Prefetch outside viewport failed");
            subscriptions.replace(player, List.of(target));
            target.setPos(player.getX() + 10, player.getEyeY(), player.getZ() + width * 1.25);
            helper.assertTrue(subscriptions.select(player, 25).contains(target), "Release margin dropped existing target");
            center.setPos(player.getX() + 10, player.getEyeY(), player.getZ());
            level.addFreshEntity(center);
            var selected = subscriptions.select(player, 25);
            helper.assertTrue(selected.size() == 1 && selected.contains(center), "Retained edge target displaced center or exceeded cap");
            center.discard();
            target.setPos(player.getX() + 10, player.getEyeY(), player.getZ() + width * 1.5);
            helper.assertTrue(!subscriptions.select(player, 25).contains(target), "Target outside release margin survived");
            subscriptions.replace(player, List.of());
            target.setPos(player.getX() + 10, player.getEyeY(), player.getZ() + width * 1.25);
            helper.assertTrue(!subscriptions.select(player, 25).contains(target), "New target used retention margin");
            subscriptions.replace(player, List.of(target));
            helper.setBlock(new BlockPos(9, 5, 4), Blocks.STONE);
            helper.assertTrue(subscriptions.select(player, 25).contains(target), "Selection unexpectedly required corridor visibility");
            helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == loaded, "Expanded selection loaded chunks");
        } finally {
            IGunOperator.fromLivingEntity(player).aim(false);
            subscriptions.replace(player, List.of());
            ScopeViews.INSTANCE.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
            target.discard(); center.discard();
            LocalBallisticsGameTests.setSettings(original);
        }
        helper.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "scope_views")
    public static void projectionHintsValidateEquipmentDimensionBoundsAndRate(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "scope-projection"));
        new LocalBallisticsGameTests.Fixture(helper, player);
        var views = ScopeViews.INSTANCE;
        var dimension = helper.getLevel().dimension().location();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var packet = new ScopeViewPayload(dimension, 25, 0.03f);
            ScopeViewPayload.STREAM_CODEC.encode(buffer, packet);
            helper.assertTrue(packet.equals(ScopeViewPayload.STREAM_CODEC.decode(buffer)), "Projection packet lost data");
            buffer.clear(); buffer.writeResourceLocation(dimension); buffer.writeFloat(25); buffer.writeFloat(Float.NaN);
            boolean rejected = false;
            try { ScopeViewPayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Nonfinite projection accepted");
            views.receive(player, new ScopeViewPayload(dimension, 6.25f, 0.03f));
            helper.assertTrue(Math.abs(views.halfAngle(player, 25) - 0.03) < 0.00001, "Actual client projection rejected by optional server zoom metadata");
            views.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
            views.receive(player, new ScopeViewPayload(Level.NETHER.location(), 25, 0.03f));
            helper.assertTrue(views.halfAngle(player, 25) == ScopeCone.halfAngle(25), "Wrong dimension accepted");
            views.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
            IGunOperator.fromLivingEntity(player).aim(false);
            views.receive(player, packet);
            helper.assertTrue(views.halfAngle(player, 25) == ScopeCone.halfAngle(25), "Unscoped hint accepted");
            views.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
            IGunOperator.fromLivingEntity(player).aim(true);
            views.receive(player, new ScopeViewPayload(dimension, 25, (float) Math.toRadians(80)));
            helper.assertTrue(views.halfAngle(player, 25) == ScopeCone.maximumHalfAngle(1), "Projection exceeded server clamp");
            views.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
            views.receive(player, packet);
            views.receive(player, new ScopeViewPayload(dimension, 25, 0.04f));
            helper.assertTrue(Math.abs(views.halfAngle(player, 25) - 0.03) < 0.00001, "Projection hint rate limit failed");
        } finally {
            buffer.release();
            views.logout(new PlayerEvent.PlayerLoggedOutEvent(player));
            IGunOperator.fromLivingEntity(player).aim(false);
        }
        helper.succeed();
    }
}
