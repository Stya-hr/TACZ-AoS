package tacz.aos.test;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.aos.block.ModContent;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@GameTestHolder("tacz_aos_lootcompat")
@PrefixGameTestTemplate(false)
public final class SpecialLootGameTests {
    private static ItemStack reward() {
        var stack = new ItemStack(Items.DIAMOND, 3);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("special-loot-test"));
        return stack;
    }

    private static void check(GameTestHelper h, Runnable before, Runnable after) {
        try {
            before.run();
            for (int mode = 0; mode < 4; mode++) {
                try (var f = new BeltLootGameTests.Fixture(h)) {
                    if (mode != 1) f.player.setItemSlot(EquipmentSlot.LEGS, ModContent.TACTICAL_COLLECTION_BELT.toStack());
                    if (mode == 2) f.victim.hurt(h.getLevel().damageSources().playerAttack(f.player), 100);
                    else f.shoot(mode == 0);
                    boolean collected = mode == 0 || mode == 3;
                    var destination = collected ? f.player.position() : f.victim.position();
                    var elsewhere = collected ? f.victim.position() : f.player.position();
                    var items = f.itemsAt(destination).stream().filter(SpecialLootGameTests::marked).toList();
                    h.assertTrue(items.stream().mapToInt(i -> i.getItem().getCount()).sum() == 3,
                            "Special reward missing, duplicated or corrupted, mode=" + mode);
                    h.assertTrue(f.itemsAt(elsewhere).stream().noneMatch(SpecialLootGameTests::marked),
                            "Special reward at wrong position, mode=" + mode);
                    h.assertTrue(f.itemsAt(f.player.position()).stream().noneMatch(i -> i.getItem().is(Items.STICK)),
                            "An unrelated item from another death listener was collected");
                }
            }
        } finally { after.run(); }
    }

    private static boolean marked(ItemEntity item) {
        return item.getItem().is(Items.DIAMOND) && item.getItem().getHoverName().getString().equals("special-loot-test");
    }

    @GameTest(template = "ballistics", batch = "special_loot")
    @SuppressWarnings("unchecked")
    public static void collectorsAlbumActualDeathInjection(GameTestHelper h) throws Exception {
        if (!ModList.get().isLoaded("collectorsalbum")) { h.succeed(); return; }
        var type = Class.forName("team.tnt.collectorsalbum.common.resource.MobAdditionalDropManager");
        var manager = type.getMethod("getInstance").invoke(null);
        var field = type.getDeclaredField("drops"); field.setAccessible(true);
        var drops = (Map<ResourceLocation, Object>)field.get(manager);
        var previous = new HashMap<>(drops);
        var provider = Class.forName("team.tnt.collectorsalbum.common.resource.drops.ItemStackDropProvider")
                .getConstructor(ItemStack.class).newInstance(reward());
        check(h, () -> { drops.clear(); drops.put(ResourceLocation.parse("tacz_aos:test"), provider); },
                () -> { drops.clear(); drops.putAll(previous); });
        h.succeed();
    }

    @GameTest(template = "ballistics", batch = "special_loot")
    @SuppressWarnings("unchecked")
    public static void lightmansCurrencyActualDeathListener(GameTestHelper h) throws Exception {
        if (!ModList.get().isLoaded("lightmanscurrency")) { h.succeed(); return; }
        var config = Class.forName("io.github.lightman314.lightmanscurrency.LCConfig").getField("COMMON").get(null);
        var fake = config.getClass().getField("allowFakePlayerCoinDrops").get(config);
        var set = fake.getClass().getMethod("set", Object.class);
        var previous = fake.getClass().getMethod("get").invoke(fake);
        var api = Class.forName("io.github.lightman314.lightmanscurrency.common.loot.modifier.ILootModifier");
        var modifier = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "tryModifyLoot" -> { var items = (List<ItemStack>)args[1]; items.clear(); items.add(reward()); yield true; }
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "AoS test loot modifier";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        var manager = Class.forName("io.github.lightman314.lightmanscurrency.common.loot.LootManager");
        set.invoke(fake, true);
        manager.getMethod("addLootModifier", api).invoke(null, modifier);
        try { check(h, () -> {}, () -> {}); }
        finally { manager.getMethod("removeLootModifier", api).invoke(null, modifier); set.invoke(fake, previous); }
        h.succeed();
    }

    @GameTest(template = "ballistics", batch = "special_loot")
    public static void championsActualSpawnHelperAndUnrelatedItems(GameTestHelper h) throws Exception {
        if (!ModList.get().isLoaded("champions")) { h.succeed(); return; }
        var type = Class.forName("top.theillusivec4.champions.common.champion.ChampionLootHandler");
        var spawn = type.getDeclaredMethod("spawnDrop", LivingEntity.class, ServerLevel.class, ItemStack.class);
        spawn.setAccessible(true);
        Consumer<LivingDeathEvent> listener = event -> {
            if (event.getEntity().level() != h.getLevel()) return;
            try { spawn.invoke(null, event.getEntity(), h.getLevel(), reward()); }
            catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
            var position = event.getEntity().position();
            h.getLevel().addFreshEntity(new ItemEntity(h.getLevel(), position.x, position.y, position.z, new ItemStack(Items.STICK)));
        };
        check(h, () -> NeoForge.EVENT_BUS.addListener(listener), () -> NeoForge.EVENT_BUS.unregister(listener));
        // No active death scope: an identical reward from the same helper must stay put.
        try (var f = new BeltLootGameTests.Fixture(h)) {
            f.player.setItemSlot(EquipmentSlot.LEGS, ModContent.TACTICAL_COLLECTION_BELT.toStack());
            spawn.invoke(null, f.victim, h.getLevel(), reward());
            h.assertTrue(f.itemsAt(f.player.position()).isEmpty() && f.itemsAt(f.victim.position()).stream().anyMatch(SpecialLootGameTests::marked),
                    "Death context leaked beyond the synchronous death");
        }
        h.succeed();
    }

    @GameTest(template = "ballistics", batch = "special_loot")
    public static void canceledDeathDoesNotCollectOrLeak(GameTestHelper h) throws Exception {
        if (!ModList.get().isLoaded("champions")) { h.succeed(); return; }
        var type = Class.forName("top.theillusivec4.champions.common.champion.ChampionLootHandler");
        var spawn = type.getDeclaredMethod("spawnDrop", LivingEntity.class, ServerLevel.class, ItemStack.class);
        spawn.setAccessible(true);
        try (var f = new BeltLootGameTests.Fixture(h)) {
            f.player.setItemSlot(EquipmentSlot.LEGS, ModContent.TACTICAL_COLLECTION_BELT.toStack());
            Consumer<LivingDeathEvent> cancel = event -> {
                if (event.getEntity() != f.victim) return;
                try { spawn.invoke(null, f.victim, h.getLevel(), reward()); }
                catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(cancel);
            try { f.victim.die(f.source()); }
            finally { NeoForge.EVENT_BUS.unregister(cancel); }
            h.assertTrue(f.itemsAt(f.player.position()).stream().noneMatch(SpecialLootGameTests::marked)
                    && f.itemsAt(f.victim.position()).stream().anyMatch(SpecialLootGameTests::marked),
                    "Canceled death transferred rewards despite not completing");
            f.itemsAt(f.victim.position()).forEach(ItemEntity::discard);
            spawn.invoke(null, f.victim, h.getLevel(), reward());
            h.assertTrue(f.itemsAt(f.player.position()).isEmpty(), "Canceled death leaked its collector");
        }
        h.succeed();
    }
}
