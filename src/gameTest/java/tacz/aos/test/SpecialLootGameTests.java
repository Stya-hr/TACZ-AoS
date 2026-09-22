package tacz.aos.test;

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
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
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
        stack.setHoverName(Component.literal("special-loot-test"));
        return stack;
    }

    private static void check(GameTestHelper h, Runnable before, Runnable after) {
        try {
            before.run();
            for (int mode = 0; mode < 4; mode++) {
                try (var f = new BeltLootGameTests.Fixture(h)) {
                    if (mode != 1) f.player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModContent.TACTICAL_COLLECTION_BELT.get()));
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

    @GameTest(setupTicks = 20, template = "ballistics", batch = "special_loot")
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
        check(h, () -> { drops.clear(); drops.put(new ResourceLocation("tacz_aos:test"), provider); },
                () -> { drops.clear(); drops.putAll(previous); });
        h.succeed();
    }

    @GameTest(setupTicks = 20, template = "ballistics", batch = "special_loot")
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

    @GameTest(setupTicks = 20, template = "ballistics", batch = "special_loot")
    @SuppressWarnings("unchecked")
    public static void championsActualGlobalLootModifier(GameTestHelper h) throws Exception {
        if (!ModList.get().isLoaded("champions")) { h.succeed(); return; }
        // Forge 2.1.10.2 appends to normal loot, unlike the later 20.x direct-spawn helper.
        var config = Class.forName("top.theillusivec4.champions.common.config.ChampionsConfig");
        var fake = config.getField("fakeLoot"); var scaling = config.getField("lootScaling");
        var source = config.getField("lootSource"); var allowList = config.getField("allowChampionsList"); Object oldAllowList = allowList.get(null);
        Object oldFake = fake.get(null), oldScaling = scaling.get(null), oldSource = source.get(null);
        var type = Class.forName("top.theillusivec4.champions.common.config.ConfigLoot");
        var field = type.getDeclaredField("DROPS"); field.setAccessible(true);
        var drops = (Map<Integer, Object>)field.get(null); var previous = new HashMap<>(drops);
        var data = Class.forName("top.theillusivec4.champions.common.config.ConfigLoot$Data")
                .getDeclaredConstructor(ItemStack.class, boolean.class, int.class);
        data.setAccessible(true);
        Object deterministic = data.newInstance(reward(), false, 1);
        Consumer<LivingDeathEvent> unrelated = event -> {
            if (event.getEntity().level() != h.getLevel()) return;
            var champion = top.theillusivec4.champions.common.capability.ChampionCapability.getCapability(event.getEntity()).orElseThrow(AssertionError::new);
            champion.getServer().setRank(top.theillusivec4.champions.common.rank.RankManager.getRank(1));
            var p = event.getEntity().position();
            h.getLevel().addFreshEntity(new ItemEntity(h.getLevel(), p.x, p.y, p.z, new ItemStack(Items.STICK)));
        };
        try {
            fake.set(null, true); scaling.set(null, false); allowList.set(null, false);
            source.set(null, source.getType().getField("CONFIG").get(null));
            drops.clear(); for (int i = 0; i <= 20; i++) drops.put(i, List.of(deterministic));
            check(h, () -> MinecraftForge.EVENT_BUS.addListener(unrelated), () -> MinecraftForge.EVENT_BUS.unregister(unrelated));
            try (var f = new BeltLootGameTests.Fixture(h)) {
                f.player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(ModContent.TACTICAL_COLLECTION_BELT.get()));
                Consumer<LivingDeathEvent> cancel = e -> { if(e.getEntity() == f.victim) e.setCanceled(true); };
                MinecraftForge.EVENT_BUS.addListener(cancel);
                try { f.victim.die(f.source()); } finally { MinecraftForge.EVENT_BUS.unregister(cancel); }
                h.assertTrue(f.itemsAt(f.player.position()).stream().noneMatch(SpecialLootGameTests::marked)
                        && f.itemsAt(f.victim.position()).stream().noneMatch(SpecialLootGameTests::marked), "Canceled death produced champion reward");
            }
        } finally {
            drops.clear(); drops.putAll(previous); fake.set(null, oldFake); scaling.set(null, oldScaling); source.set(null, oldSource); allowList.set(null, oldAllowList);
        }
        h.succeed();
    }
}
