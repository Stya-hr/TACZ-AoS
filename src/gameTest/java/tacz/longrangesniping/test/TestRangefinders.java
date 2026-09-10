package tacz.longrangesniping.test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import tacz.longrangesniping.block.ModContent;

final class TestRangefinders {
    private TestRangefinders() {}
    static BlockPos placeNear(Player player) {
        var pos = player.blockPosition().offset(0, -1, 2);
        var block = ModContent.RANGEFINDER.get();
        player.level().setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        player.level().setBlockAndUpdate(pos, block.defaultBlockState());
        block.setPlacedBy(player.level(), pos, block.defaultBlockState(), player, new ItemStack(block));
        return pos;
    }
}
