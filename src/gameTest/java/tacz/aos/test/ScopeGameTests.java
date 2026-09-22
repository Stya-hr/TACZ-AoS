package tacz.aos.test;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ServerConfig;
import tacz.aos.network.ScopeTargetsPayload;
import tacz.aos.scoping.ScopeSubscriptions;


import java.util.List;
import java.util.UUID;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScopeGameTests {
    @GameTest(setupTicks = 20, template = "ballistics", batch = "scope")
    public static void bossesDisplaysAndItemsAreSelectable(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "scope-types"));
        new LocalBallisticsGameTests.Fixture(helper, player);
        player.setPos(helper.absoluteVec(new Vec3(4, 4, 4)));
        player.setYRot(-90); player.setYHeadRot(-90); player.setXRot(0);
        for (var type : List.of(EntityType.COW, EntityType.WARDEN, EntityType.WITHER, EntityType.ENDER_DRAGON,
                EntityType.ARMOR_STAND, EntityType.ITEM_FRAME, EntityType.ITEM_DISPLAY, EntityType.BLOCK_DISPLAY,
                EntityType.TEXT_DISPLAY, EntityType.ITEM)) {
            var entity = type.create(helper.getLevel());
            entity.setPos(player.getX() + 10, player.getEyeY() - entity.getEyeHeight(), player.getZ());
            if (entity instanceof LivingEntity living && living instanceof net.minecraft.world.entity.Mob mob) mob.setNoAi(true);
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity item)
                item.setItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
            helper.getLevel().addFreshEntity(entity);
            try {
                helper.assertTrue(ScopeSubscriptions.INSTANCE.select(player, 25).contains(entity), "Entity type still filtered: " + type);
            } finally { entity.discard(); }
        }
        IGunOperator.fromLivingEntity(player).aim(false);
        helper.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "scope")
    public static void selectionObeysAimWallsAndObserverHandoff(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "scope-test"));
        var fixture = new LocalBallisticsGameTests.Fixture(helper, player);
        player.setPos(helper.absoluteVec(new Vec3(4, 4, 4)));
        player.setYRot(-90); player.setYHeadRot(-90); player.setXRot(0);
        // Deliberately empty native chunk tracking, to exercise the additional observer path.
        player.setLastSectionPos(net.minecraft.core.SectionPos.of(100000, 0, 100000));
        var cow = helper.spawn(EntityType.COW, new Vec3(14, 4.32, 4));
        cow.setNoAi(true); cow.setNoGravity(true);
        var behind = helper.spawn(EntityType.DONKEY, new Vec3(1, 4.32, 4));
        behind.setNoAi(true); behind.setNoGravity(true);
        var subscriptions = ScopeSubscriptions.INSTANCE;
        int loaded = level.getChunkSource().getLoadedChunksCount();
        try {
            var selected = subscriptions.select(player, 25);
            helper.assertTrue(selected.contains(cow) && !selected.contains(behind), "Aim cone selection failed: selected=" + selected
                    + ", eye=" + player.getEyePosition() + ", look=" + player.getViewVector(1) + ", cow=" + cow.position()
                    );
            var tracker = ScopeSubscriptions.tracker(level, cow.getId());
            helper.assertTrue(tracker != null, "No native tracker");
            tracker.tacz_aos$removePlayer(player);
            subscriptions.replace(player, List.of(cow));
            helper.assertTrue(subscriptions.authorized(player, cow) && tracker.tacz_aos$seenBy().contains(player.connection), "Scope did not add observer");
            int observers = tracker.tacz_aos$seenBy().size();
            subscriptions.replace(player, List.of(cow));
            helper.assertTrue(tracker.tacz_aos$seenBy().size() == observers, "Duplicate observer created");

            var gun = IGun.getIGunOrNull(fixture.stack);
            var tag = gun.getAttachmentTag(fixture.stack, AttachmentType.SCOPE).copy();
            tag.putInt("ZoomNumber", 0); gun.getAttachmentTag(fixture.stack, AttachmentType.SCOPE).merge(tag);
            helper.assertTrue(subscriptions.authorized(player, cow), "Low magnification lost authorization");
            IGunOperator.fromLivingEntity(player).aim(false);
            helper.assertTrue(!subscriptions.authorized(player, cow), "Unscoping kept authorization");
            // 1.20.1 entity tracking uses physical distance, independently of chunk watching.
            var nearPosition = player.position();
            player.setPos(nearPosition.add(1000, 0, 0));
            subscriptions.replace(player, List.of());
            helper.assertTrue(!tracker.tacz_aos$seenBy().contains(player.connection), "Extra observer survived release");
            player.setPos(nearPosition);
            tag.putInt("ZoomNumber", 1); gun.getAttachmentTag(fixture.stack, AttachmentType.SCOPE).merge(tag);
            IGunOperator.fromLivingEntity(player).aim(true);

            // Returning to normal tracking must keep the same entity observer when the scope ends.
            player.setLastSectionPos(net.minecraft.core.SectionPos.of(player));
            subscriptions.replace(player, List.of(cow));
            IGunOperator.fromLivingEntity(player).aim(false);
            subscriptions.replace(player, List.of());
            helper.assertTrue(tracker.tacz_aos$seenBy().contains(player.connection), "Unscope removed a normally tracked entity");
            IGunOperator.fromLivingEntity(player).aim(true);

            helper.setBlock(new BlockPos(10, 5, 4), Blocks.STONE);
            helper.setBlock(new BlockPos(10, 4, 4), Blocks.STONE);
            helper.assertTrue(subscriptions.select(player, 25).contains(cow), "Selection unexpectedly queried corridor walls");
            helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == loaded, "Scope selection loaded chunks");
        } finally {
            IGunOperator.fromLivingEntity(player).aim(false);
            subscriptions.replace(player, List.of());
            var tracker = ScopeSubscriptions.tracker(level, cow.getId());
            if (tracker != null) tracker.tacz_aos$removePlayer(player);
            cow.discard(); behind.discard();
        }
        helper.succeed();
    }



    @GameTest(setupTicks = 20, template = "empty", batch = "scope")
    public static void boundedPayloadRoundTripsIdentityPoseAndLight(GameTestHelper helper) {
        var target = new ScopeTargetsPayload.Target(42, UUID.randomUUID(), 512.25, 80, -30, 90, 10, 95, 89, 15728880, true);
        var packet = new ScopeTargetsPayload(helper.getLevel().dimension().location(), 4, List.of(target));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ScopeTargetsPayload.STREAM_CODEC.encode(buffer, packet);
            helper.assertTrue(packet.equals(ScopeTargetsPayload.STREAM_CODEC.decode(buffer)), "Scope packet lost data");
            buffer.clear(); buffer.writeResourceLocation(packet.dimension()); buffer.writeVarInt(4); buffer.writeVarInt(257);
            boolean rejected = false;
            try { ScopeTargetsPayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Unbounded target packet accepted");
        } finally { buffer.release(); }
        helper.succeed();
    }
}
