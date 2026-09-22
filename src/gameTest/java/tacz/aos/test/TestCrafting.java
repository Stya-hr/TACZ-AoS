package tacz.aos.test;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.entity.player.Player;

final class TestCrafting {
    static TransientCraftingContainer grid(int width, int height, List<ItemStack> stacks) {
        var menu = new AbstractContainerMenu(null, 0) {
            public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
            public boolean stillValid(Player player) { return true; }
        };
        var grid = new TransientCraftingContainer(menu, width, height);
        for (int i = 0; i < stacks.size(); i++) grid.setItem(i, stacks.get(i));
        return grid;
    }
}
