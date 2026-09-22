package tacz.aos.test;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Run alongside actual optional mods without assumptions about vanilla-only drops or mock-player capabilities. */
@GameTestHolder("tacz_aos_lootcompat")
@PrefixGameTestTemplate(false)
public final class OptionalEquipmentGameTests {
    @GameTest(setupTicks=20, template="ballistics", batch="optional_equipment")
    public static void curios(GameTestHelper h) throws Exception {
        BeltLootGameTests.curiosIsOptionalAndOnlyExistingActiveBeltSlotCollects(h);
    }
    @GameTest(setupTicks=20, template="ballistics", batch="optional_equipment")
    public static void parcool(GameTestHelper h) throws Exception {
        SnipingPhysicsGameTests.optionalParCoolActionControlsActualBulletSpread(h);
    }
    @GameTest(setupTicks=20, template="ballistics", batch="optional_equipment")
    public static void crawlOnDemand(GameTestHelper h) throws Exception {
        SnipingPhysicsGameTests.optionalCrawlOnDemandControlsBothLaunchRoutes(h);
    }
}
