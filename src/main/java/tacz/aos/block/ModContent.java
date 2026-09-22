package tacz.aos.block;

import com.tacz.guns.init.ModCreativeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.DeferredRegister;
import tacz.aos.TaczArtOfSniping;

public final class ModContent {
    private static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, TaczArtOfSniping.MOD_ID);
    private static final DeferredRegister<net.minecraft.world.item.Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, TaczArtOfSniping.MOD_ID);
    private static final SoundType RANGEFINDER_SOUND = new SoundType(SoundType.WOOD.getVolume(), SoundType.WOOD.getPitch(),
            SoundType.ANVIL.getBreakSound(), SoundType.WOOD.getStepSound(), SoundType.WOOD.getPlaceSound(),
            SoundType.WOOD.getHitSound(), SoundType.WOOD.getFallSound());
    // Keep the original workbench sounds except for breaking.
    public static final RegistryObject<RangefinderBlock> RANGEFINDER = BLOCKS.register("rangefinder", () -> new RangefinderBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).sound(RANGEFINDER_SOUND).strength(2, 3)
                    .noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final RegistryObject<BlockItem> RANGEFINDER_ITEM = ITEMS.register("rangefinder", () -> new BlockItem(RANGEFINDER.get(), new net.minecraft.world.item.Item.Properties()));
    public static final RegistryObject<tacz.aos.item.TacticalCollectionBeltItem> TACTICAL_COLLECTION_BELT = ITEMS.register(
            "tactical_collection_belt", () -> new tacz.aos.item.TacticalCollectionBeltItem(new net.minecraft.world.item.Item.Properties()));
    private ModContent() {}
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); bus.addListener(ModContent::creative); }
    private static void creative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.OTHER_TAB.getKey())) {
            event.accept(RANGEFINDER_ITEM);
            event.accept(TACTICAL_COLLECTION_BELT);
        }
    }
}
