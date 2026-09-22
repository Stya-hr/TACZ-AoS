package tacz.aos.test;

import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityPowerPoint;
import com.github.tartaricacid.touhoulittlemaid.entity.monster.EntityFairy;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.aos.block.ModContent;

import java.util.function.Consumer;

@GameTestHolder("tacz_aos_lootcompat")
@PrefixGameTestTemplate(false)
public final class MaidPowerPointGameTests {
    @GameTest(template = "ballistics", batch = "maid_power")
    public static void nativeAndManagedShotsKeepDelayedPowerValues(GameTestHelper h) throws Exception {
        if (ModList.get().isLoaded("touhou_little_maid")) Checks.positive(h);
        h.succeed();
    }

    @GameTest(template = "ballistics", batch = "maid_power")
    public static void ineligibleKillsAndMissingCollectorKeepFairyPosition(GameTestHelper h) throws Exception {
        if (ModList.get().isLoaded("touhou_little_maid")) Checks.negative(h);
        h.succeed();
    }

    @GameTest(template = "ballistics", batch = "maid_power")
    public static void mobLootRuleAndCanceledSpawnsRemainEffective(GameTestHelper h) throws Exception {
        if (ModList.get().isLoaded("touhou_little_maid")) Checks.canceled(h);
        h.succeed();
    }

    // Isolate optional mod class references from GameTest discovery when the mod is absent.
    private static final class Checks {
        static void deathTicks(EntityFairy fairy, int count) throws Exception {
            var method = EntityFairy.class.getDeclaredMethod("tickDeath");
            method.setAccessible(true);
            for (int i = 0; i < count; i++) method.invoke(fairy);
        }

        static int power(GameTestHelper h, Vec3 p) {
            return h.getLevel().getEntitiesOfClass(EntityPowerPoint.class, new AABB(p, p).inflate(2))
                    .stream().mapToInt(EntityPowerPoint::getValue).sum();
        }

        static void clear(GameTestHelper h, BeltLootGameTests.Fixture f) {
            for (var p : new Vec3[]{f.player.position(), f.victim.position()}) {
                h.getLevel().getEntitiesOfClass(EntityPowerPoint.class, new AABB(p, p).inflate(2))
                        .forEach(EntityPowerPoint::discard);
            }
        }

        static void positive(GameTestHelper h) throws Exception {
            for (int mode = 0; mode < 2; mode++) {
                try (var f = new BeltLootGameTests.Fixture(h, EntityFairy.TYPE)) {
                    try {
                        var fairy = (EntityFairy)f.victim;
                        fairy.setBaby(mode == 1);
                        // Aim through the smaller baby's body too.
                        f.bullet.setPos(f.player.position().add(0, .25, 0));
                        f.player.setItemSlot(EquipmentSlot.LEGS, ModContent.TACTICAL_COLLECTION_BELT.toStack());
                        int expected = fairy.getPowerPoint();
                        h.assertTrue(expected > 0, "Fairy test configured with zero power");
                        f.shoot(mode == 1);
                        deathTicks(fairy, 19);
                        h.assertTrue(power(h, f.player.position()) == 0 && power(h, fairy.position()) == 0, "P points generated prematurely");
                        // Eligibility is fixed at the fatal shot; destination follows the living shooter.
                        f.player.setPos(f.player.position().add(1, 0, 0));
                        f.player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
                        deathTicks(fairy, 1);
                        h.assertTrue(power(h, f.player.position()) == expected && power(h, fairy.position()) == 0,
                                "P value lost, duplicated, converted to XP or left at fairy");
                    } finally { clear(h, f); }
                }
            }
        }

        static void negative(GameTestHelper h) throws Exception {
            for (int mode = 0; mode < 4; mode++) {
                try (var f = new BeltLootGameTests.Fixture(h, EntityFairy.TYPE)) {
                    try {
                        var fairy = (EntityFairy)f.victim;
                        int expected = fairy.getPowerPoint();
                        if (mode != 0) f.player.setItemSlot(EquipmentSlot.LEGS, ModContent.TACTICAL_COLLECTION_BELT.toStack());
                        if (mode == 1) fairy.hurt(h.getLevel().damageSources().playerAttack(f.player), 100);
                        else if (mode == 2) {
                            fairy.setHealth(20); fairy.hurt(f.source(), 1); fairy.invulnerableTime = 0;
                            fairy.hurt(h.getLevel().damageSources().generic(), 100);
                        } else f.shoot(false);
                        if (mode == 3) f.player.discard();
                        deathTicks(fairy, 20);
                        h.assertTrue(power(h, f.player.position()) == 0 && power(h, fairy.position()) == expected,
                                "Ineligible or removed collector received P points, mode=" + mode);
                    } finally { clear(h, f); }
                }
            }
        }

        static void canceled(GameTestHelper h) throws Exception {
            var rule = h.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBLOOT);
            boolean previous = rule.get();
            for (int mode = 0; mode < 2; mode++) {
                try (var f = new BeltLootGameTests.Fixture(h, EntityFairy.TYPE)) {
                    Consumer<EntityJoinLevelEvent> cancel = e -> { if (e.getEntity() instanceof EntityPowerPoint) e.setCanceled(true); };
                    try {
                        f.player.setItemSlot(EquipmentSlot.LEGS, ModContent.TACTICAL_COLLECTION_BELT.toStack());
                        if (mode == 0) rule.set(false, h.getLevel().getServer());
                        else NeoForge.EVENT_BUS.addListener(cancel);
                        f.shoot(true); deathTicks((EntityFairy)f.victim, 20);
                        h.assertTrue(power(h, f.player.position()) == 0 && power(h, f.victim.position()) == 0, "Suppressed P points recreated");
                    } finally {
                        rule.set(previous, h.getLevel().getServer());
                        if (mode == 1) NeoForge.EVENT_BUS.unregister(cancel);
                        clear(h, f);
                    }
                }
            }
        }
    }
}
