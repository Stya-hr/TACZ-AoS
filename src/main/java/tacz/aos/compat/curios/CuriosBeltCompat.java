package tacz.aos.compat.curios;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import tacz.aos.block.ModContent;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/** Only loaded behind a ModList check. Registers an item, never a slot or slot modifier. */
public final class CuriosBeltCompat {
    private CuriosBeltCompat() {}

    public static void register() {
        CuriosApi.registerCurio(ModContent.TACTICAL_COLLECTION_BELT.get(), new ICurioItem() {
            @Override public boolean canEquip(SlotContext context, ItemStack stack) {
                return "belt".equals(context.identifier());
            }
        });
    }

    public static boolean isEquipped(Player player) {
        var inventory = CuriosApi.getCuriosInventory(player).orElse(null);
        if (inventory == null) return false;
        var belt = inventory.getCurios().get("belt");
        if (belt == null) return false;
        // Cosmetic slots and disabled slots do not grant gameplay effects.
        var stacks = belt.getStacks();
        for (int i = 0; i < stacks.getSlots(); i++) {
            if (inventory.isSlotActive("belt", i) && stacks.getStackInSlot(i).is(ModContent.TACTICAL_COLLECTION_BELT)) return true;
        }
        return false;
    }
}
