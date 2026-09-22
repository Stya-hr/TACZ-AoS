package tacz.aos.test;

import com.mojang.authlib.GameProfile;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.init.ModDamageTypes;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.ballistics.TargetBallistics;
import tacz.aos.ballistics.TargetCollisionSource;
import tacz.aos.block.ModContent;
import tacz.aos.loot.BeltLootCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BeltLootGameTests {
    @GameTest(setupTicks = 20, template = "ballistics", batch = "belt_loot")
    public static void recipeAndVanillaLegsEquipment(GameTestHelper h) {
        var f = new Fixture(h);
        h.assertTrue(f.player.inventoryMenu.getSlot(7).mayPlace(belt()) && !f.player.inventoryMenu.getSlot(6).mayPlace(belt()),
                "Inventory armor slots do not recognize the belt as leg equipment");
        f.player.setItemInHand(InteractionHand.MAIN_HAND, belt());
        ModContent.TACTICAL_COLLECTION_BELT.get().use(h.getLevel(), f.player, InteractionHand.MAIN_HAND);
        h.assertTrue(BeltLootCollection.isEquipped(f.player) && f.player.getItemBySlot(EquipmentSlot.LEGS).is(ModContent.TACTICAL_COLLECTION_BELT.get()),
                "Right click did not equip the vanilla legs slot");
        var slots = new ArrayList<ItemStack>();
        for (int i = 0; i < 9; i++) slots.add(ItemStack.EMPTY);
        slots.set(1, new ItemStack(Items.DIAMOND)); slots.set(3, new ItemStack(Items.LEATHER));
        slots.set(4, new ItemStack(Items.REDSTONE)); slots.set(5, new ItemStack(Items.LEATHER));
        slots.set(7, new ItemStack(Items.GOLD_INGOT));
        var input = TestCrafting.grid(3, 3, slots);
        var recipe = h.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, h.getLevel()).orElseThrow();
        h.assertTrue(recipe.assemble(input, h.getLevel().registryAccess()).is(ModContent.TACTICAL_COLLECTION_BELT.get()), "Wrong crafting recipe");
        f.close(); h.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "belt_loot")
    public static void nativeAndDetachedGunKillsMoveActualItemsAndModifiedExperience(GameTestHelper h) {
        for (boolean managed : List.of(false, true)) {
            try (var f = new Fixture(h)) {
                f.player.setItemSlot(EquipmentSlot.LEGS, belt());
                Consumer<LivingDropsEvent> modify = e -> {
                    if (e.getEntity() != f.victim) return;
                    var stack = new ItemStack(Items.DIAMOND, 3);
                    stack.setHoverName(Component.literal("kept-components"));
                    e.getDrops().add(new ItemEntity(h.getLevel(), f.victim.getX(), f.victim.getY(), f.victim.getZ(), stack));
                };
                Consumer<LivingExperienceDropEvent> xp = e -> { if (e.getEntity() == f.victim) e.setDroppedExperience(17); };
                MinecraftForge.EVENT_BUS.addListener(modify); MinecraftForge.EVENT_BUS.addListener(xp);
                try {
                    // No rangefinder, no scope, a non-sniper gun, and the gun can be put away during flight.
                    f.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    f.shoot(managed);
                    var items = f.itemsAt(f.player.position());
                    h.assertTrue(items.stream().anyMatch(i -> i.getItem().is(Items.DIAMOND) && i.getItem().getCount() == 3
                                    && i.getItem().getHoverName().getString().equals("kept-components")), "Actual modified drops were lost");
                    h.assertTrue(f.itemsAt(f.victim.position()).isEmpty(), "Loot remained at target");
                    h.assertTrue(f.xpAt(f.player.position()) == 17 && f.xpAt(f.victim.position()) == 0, "XP lost, duplicated or left at target");
                } finally { MinecraftForge.EVENT_BUS.unregister(modify); MinecraftForge.EVENT_BUS.unregister(xp); }
            }
        }
        h.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "belt_loot")
    public static void unequippedMeleeAndLaterEnvironmentalDeathsKeepNormalDrops(GameTestHelper h) {
        for (int mode = 0; mode < 3; mode++) {
            try (var f = new Fixture(h)) {
                // Merely carrying a belt is insufficient.
                f.player.getInventory().add(belt());
                if (mode > 0) f.player.setItemSlot(EquipmentSlot.LEGS, belt());
                if (mode == 0) f.shoot(false);
                else if (mode == 1) f.victim.hurt(h.getLevel().damageSources().playerAttack(f.player), 100);
                else {
                    f.victim.setHealth(20);
                    f.victim.hurt(f.source(), 1);
                    f.victim.invulnerableTime = 0;
                    f.victim.hurt(h.getLevel().damageSources().fall(), 100);
                }
                h.assertTrue(f.itemsAt(f.player.position()).isEmpty() && f.xpAt(f.player.position()) == 0, "Collected an ineligible kill");
                h.assertTrue(!f.itemsAt(f.victim.position()).isEmpty(), "Normal guaranteed equipment drop missing");
            }
        }
        h.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "belt_loot")
    public static void canceledDropsAndExperienceStayCanceled(GameTestHelper h) {
        try (var f = new Fixture(h)) {
            f.player.setItemSlot(EquipmentSlot.LEGS, belt());
            Consumer<LivingDropsEvent> drops = e -> { if (e.getEntity() == f.victim) e.setCanceled(true); };
            Consumer<LivingExperienceDropEvent> xp = e -> { if (e.getEntity() == f.victim) e.setCanceled(true); };
            MinecraftForge.EVENT_BUS.addListener(drops); MinecraftForge.EVENT_BUS.addListener(xp);
            try {
                f.shoot(true);
                h.assertTrue(f.itemsAt(f.player.position()).isEmpty() && f.itemsAt(f.victim.position()).isEmpty()
                        && f.xpAt(f.player.position()) == 0 && f.xpAt(f.victim.position()) == 0, "Canceled loot was recreated");
            } finally { MinecraftForge.EVENT_BUS.unregister(drops); MinecraftForge.EVENT_BUS.unregister(xp); }
        }
        h.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "belt_loot")
    public static void curiosIsOptionalAndOnlyExistingActiveBeltSlotCollects(GameTestHelper h) throws ReflectiveOperationException {
        if (ModList.get().isLoaded("curios")) CuriosChecks.run(h);
        else {
            try (var f = new Fixture(h)) {
                h.assertTrue(!BeltLootCollection.isEquipped(f.player), "Belt appeared without equipment");
                f.player.setItemSlot(EquipmentSlot.LEGS, belt()); f.shoot(true);
                h.assertTrue(!f.itemsAt(f.player.position()).isEmpty(), "No-Curios fallback failed");
            }
        }
        h.succeed();
    }

    private static ItemStack belt() { return new ItemStack(ModContent.TACTICAL_COLLECTION_BELT.get()); }

    private static final class CuriosChecks {
        @SuppressWarnings("unchecked")
        static void run(GameTestHelper h) throws ReflectiveOperationException {
            // Curios intentionally supplies no inventory capability until an entity has an assigned slot.
            var manager = top.theillusivec4.curios.common.data.CuriosEntityManager.SERVER;
            var field = manager.getClass().getDeclaredField("entitySlots"); field.setAccessible(true);
            var previous = (java.util.Map<EntityType<?>, java.util.Map<String, top.theillusivec4.curios.api.type.ISlotType>>)field.get(manager);
            h.assertTrue(!top.theillusivec4.curios.api.CuriosApi.getEntitySlots(EntityType.PLAYER, h.getLevel()).containsKey("belt"),
                    "Production mod added a Curios belt slot");
            var types = new java.util.HashMap<>(previous);
            types.put(EntityType.PLAYER, java.util.Map.of("belt", new top.theillusivec4.curios.common.slottype.SlotType.Builder("belt")
                    .size(1).hasCosmetic(true).build()));
            field.set(manager, types);
            try (var f = new Fixture(h)) {
                var inv = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(f.player).orElseThrow(AssertionError::new);
                var slot = inv.getStacksHandler("belt").orElseThrow();
                var context = new top.theillusivec4.curios.api.SlotContext("belt", f.player, 0, false, true);
                h.assertTrue(top.theillusivec4.curios.api.CuriosApi.isStackValid(context, belt())
                        && top.theillusivec4.curios.api.CuriosApi.getCurio(belt()).orElseThrow(AssertionError::new).canEquip(context), "Curios item/tag registration failed");
                slot.getCosmeticStacks().setStackInSlot(0, belt());
                h.assertTrue(!BeltLootCollection.isEquipped(f.player), "Cosmetic belt grants collection");
                slot.getStacks().setStackInSlot(0, belt());
                h.assertTrue(BeltLootCollection.isEquipped(f.player), "Active Curios belt not detected");
                f.shoot(true);
                h.assertTrue(!f.itemsAt(f.player.position()).isEmpty() && f.xpAt(f.player.position()) > 0, "Curios did not collect loot/XP");
            } finally { field.set(manager, previous); }
        }
    }

    static final class Fixture implements AutoCloseable {
        final GameTestHelper h;
        final FakePlayer player;
        final Mob victim;
        final EntityKineticBullet bullet;
        Fixture(GameTestHelper h) {
            this(h, EntityType.ZOMBIE);
        }
        Fixture(GameTestHelper h, EntityType<? extends Mob> victimType) {
            this.h = h;
            player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), "belt-test"));
            player.setPos(h.absoluteVec(new Vec3(4, 4, 4)));
            player.setOldPosAndRot();
            var id = new ResourceLocation("tacz:ak47");
            var stack = GunItemBuilder.create().setId(id).setAmmoCount(30).build();
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            var data = TimelessAPI.getCommonGunIndex(id).orElseThrow().getGunData();
            var operator = IGunOperator.fromLivingEntity(player); operator.initialData();
            var cache = new AttachmentCacheProperty(); cache.eval(stack, data); operator.updateCacheProperty(cache); operator.aim(false);
            victim = h.spawn(victimType, new Vec3(12, 4, 4)); victim.setNoAi(true); victim.setHealth(1);
            victim.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET)); victim.setDropChance(EquipmentSlot.HEAD, 2);
            bullet = new EntityKineticBullet(h.getLevel(), player, stack, data.getAmmoId(), id, false, data, data.getBulletData());
            bullet.setPos(player.position().add(0, .7, 0)); bullet.setDeltaMovement(10, 0, 0);
            LocalBallisticsGameTests.setField(bullet, "startPos", bullet.position());
        }
        net.minecraft.world.damagesource.DamageSource source() {
            return ModDamageTypes.Sources.bullet(h.getLevel().registryAccess(), bullet, player, false);
        }
        void shoot(boolean managed) {
            if (managed) {
                bullet.setOldPosAndRot(); bullet.tickCount++;
                TargetBallistics.tick(h.getLevel(), bullet, 128,
                        new TargetCollisionSource(h.getLevel(), List.of(victim.getBoundingBox().inflate(2))));
            } else h.getLevel().tickNonPassenger(bullet);
            h.assertTrue(victim.isDeadOrDying(), "Real TACZ projectile failed to kill fixture: health=" + victim.getHealth() + " target=" + victim.position() + " bullet=" + bullet.position());
        }
        List<ItemEntity> itemsAt(Vec3 p) { return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(p, p).inflate(2)); }
        int xpAt(Vec3 p) {
            return h.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(p, p).inflate(2)).stream().mapToInt(orb -> {
                var nbt = new CompoundTag(); orb.saveWithoutId(nbt); return orb.getValue() * nbt.getInt("Count");
            }).sum();
        }
        public void close() {
            for (var p : List.of(player.position(), victim.position())) {
                itemsAt(p).forEach(ItemEntity::discard);
                h.getLevel().getEntitiesOfClass(ExperienceOrb.class, new AABB(p, p).inflate(2)).forEach(ExperienceOrb::discard);
            }
            bullet.discard(); victim.discard(); player.discard();
        }
    }
}
