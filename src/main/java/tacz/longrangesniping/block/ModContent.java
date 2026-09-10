package tacz.longrangesniping.block;

import com.tacz.guns.init.ModCreativeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import tacz.longrangesniping.TaczLongRangeSniping;

public final class ModContent {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TaczLongRangeSniping.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TaczLongRangeSniping.MOD_ID);
    private static final SoundType RANGEFINDER_SOUND = new SoundType(SoundType.WOOD.getVolume(), SoundType.WOOD.getPitch(),
            SoundType.ANVIL.getBreakSound(), SoundType.WOOD.getStepSound(), SoundType.WOOD.getPlaceSound(),
            SoundType.WOOD.getHitSound(), SoundType.WOOD.getFallSound());
    // Keep the original workbench sounds except for breaking.
    public static final DeferredBlock<RangefinderBlock> RANGEFINDER = BLOCKS.register("rangefinder", () -> new RangefinderBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).sound(RANGEFINDER_SOUND).strength(2, 3)
                    .noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final DeferredItem<BlockItem> RANGEFINDER_ITEM = ITEMS.registerSimpleBlockItem(RANGEFINDER);
    private ModContent() {}
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); bus.addListener(ModContent::creative); }
    private static void creative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.OTHER_TAB.getKey())) event.accept(RANGEFINDER_ITEM);
    }
}
