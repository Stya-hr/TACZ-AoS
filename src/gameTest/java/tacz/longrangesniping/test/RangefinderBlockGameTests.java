package tacz.longrangesniping.test;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.longrangesniping.TaczLongRangeSniping;
import tacz.longrangesniping.block.ModContent;
import tacz.longrangesniping.block.RangefinderBlock;
import java.util.List;
import java.util.UUID;

@GameTestHolder(TaczLongRangeSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RangefinderBlockGameTests {
    @GameTest(template = "ballistics", batch = "rangefinder_block")
    public static void placementEitherHalfAndSupportDropOne(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(4, 3, 4));
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "range-block-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        player.setPos(Vec3.atCenterOf(pos).add(0, 0, 2));
        for (int mode = 0; mode < 4; mode++) {
            level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4)).forEach(ItemEntity::discard);
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
            var stack = new ItemStack(ModContent.RANGEFINDER_ITEM.get());
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            player.setYRot(mode * 90);
            var hit = new BlockHitResult(Vec3.atBottomCenterOf(pos), Direction.UP, pos.below(), false);
            var result = stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            helper.assertTrue(result.consumesAction() && stack.isEmpty(), "Placement failed or consumed wrong count");
            var lower = level.getBlockState(pos); var upper = level.getBlockState(pos.above());
            helper.assertTrue(lower.is(ModContent.RANGEFINDER.get()) && upper.is(ModContent.RANGEFINDER.get())
                    && lower.getValue(RangefinderBlock.HALF) == DoubleBlockHalf.LOWER
                    && upper.getValue(RangefinderBlock.HALF) == DoubleBlockHalf.UPPER
                    && upper.getValue(RangefinderBlock.FACING) == player.getDirection().getOpposite(), "Incorrect halves/facing");
            if (mode == 0) player.gameMode.destroyBlock(pos);
            if (mode == 1) player.gameMode.destroyBlock(pos.above());
            if (mode == 2) level.destroyBlock(pos.below(), false);
            if (mode == 3) {
                player.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                player.gameMode.destroyBlock(pos.above());
            }
            helper.assertTrue(level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir(), "Orphaned half: " + mode);
            int drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4)).stream()
                    .filter(e -> e.getItem().is(ModContent.RANGEFINDER_ITEM.get())).mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(drops == (mode == 3 ? 0 : 1), "Wrong drop count for removal " + mode + ": " + drops);
        }
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "rangefinder_block")
    public static void blockedPlacementRecipeAndAnvilSound(GameTestHelper helper) {
        var player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "range-placement-test"));
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        var pos = helper.absolutePos(new BlockPos(4, 3, 4));
        var level = helper.getLevel();
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos.above(), Blocks.STONE.defaultBlockState());
        var stack = new ItemStack(ModContent.RANGEFINDER_ITEM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var hit = new BlockHitResult(Vec3.atBottomCenterOf(pos), Direction.UP, pos.below(), false);
        stack.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        helper.assertTrue(stack.getCount() == 1 && level.getBlockState(pos).isAir(), "Obstructed placement consumed item or left lower half");
        var recipe = level.getRecipeManager().byKey(ResourceLocation.parse("tacz_long_range_sniping:rangefinder")).orElseThrow();
        var shaped = (net.minecraft.world.item.crafting.ShapedRecipe) recipe.value();
        var input = CraftingInput.of(3,3, List.of(new ItemStack(Items.GLASS_PANE), new ItemStack(Items.DIAMOND), new ItemStack(Items.GLASS_PANE),
                ItemStack.EMPTY, new ItemStack(Items.IRON_INGOT), ItemStack.EMPTY,
                new ItemStack(Items.IRON_INGOT), ItemStack.EMPTY, new ItemStack(Items.IRON_INGOT)));
        helper.assertTrue(shaped.matches(input, level) && shaped.assemble(input, level.registryAccess()).is(ModContent.RANGEFINDER_ITEM.get()),
                "Requested ABA / XCX / CXC recipe not available");
        helper.assertTrue(ModContent.RANGEFINDER.get().defaultBlockState().getSoundType().getBreakSound()
                == Blocks.ANVIL.defaultBlockState().getSoundType().getBreakSound(), "Anvil sound mismatch");
        helper.succeed();
    }
}
