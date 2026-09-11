package tacz.aos.test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.ballistics.TargetCollisionSource;
import tacz.aos.ballistics.SegmentTrace;
import tacz.aos.block.ModContent;
import tacz.aos.block.RangefinderBlock;
import tacz.aos.compat.tacz.TaczBridge;
import tacz.aos.scoping.ScopeRangefinder;

import tacz.aos.terrain.*;
import java.util.List;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RangefinderCollisionGameTests {
    @GameTest(template = "ballistics", batch = "rangefinder_collision")
    public static void nearbyInstrumentDoesNotChangeThePlayersRangeRay(GameTestHelper helper) {
        var fixture = new LocalBallisticsGameTests.Fixture(helper);
        var player = fixture.player;
        var level = helper.getLevel();
        level.removeBlock(player.blockPosition().offset(0, -1, 2), false);
        var block = ModContent.RANGEFINDER.get();
        var base = helper.absolutePos(new BlockPos(8, 4, 8));
        level.setBlockAndUpdate(base.below(), Blocks.STONE.defaultBlockState());
        for (int y = 4; y <= 7; y++) for (int z = 5; z <= 11; z++) helper.setBlock(new BlockPos(20, y, z), Blocks.STONE);
        int loaded = level.getChunkSource().getLoadedChunksCount();
        for (var facing : Direction.Plane.HORIZONTAL) {
            var state = block.defaultBlockState().setValue(RangefinderBlock.FACING, facing);
            level.setBlockAndUpdate(base, state);
            block.setPlacedBy(level, base, state, player, new ItemStack(block));
            for (double x : new double[] {5.5, 7.5, 8.5, 9.5, 11.9}) {
                player.setPos(helper.absoluteVec(new Vec3(x, 4, 7.8)));
                player.setYRot(-90); player.setYHeadRot(-90); player.setXRot(0); player.setOldPosAndRot();
                helper.assertTrue(TaczBridge.evaluate(player).eligible(), "Nearby position lost access: " + x);
                var eye = player.getEyePosition();
                var direction = player.getViewVector(1);
                float distance = ScopeRangefinder.measure(eye, direction, new TargetCollisionSource(level,List.of(new net.minecraft.world.phys.AABB(base).inflate(64))), List.of(),
                        64, 65536, Long.MAX_VALUE);
                helper.assertTrue(Math.abs(distance - (20 - x)) < .001,
                        "Device changed eye-origin range: facing=" + facing + ", x=" + x + ", distance=" + distance + ", eye=" + eye + ", direction=" + direction);
                var trace = SegmentTrace.trace(new TargetCollisionSource(level,List.of(new net.minecraft.world.phys.AABB(base).inflate(64))), eye.x, eye.y, eye.z, 19 - x, 0, 0);
                helper.assertTrue(trace.end() == SegmentTrace.End.CLEAR, "Instrument halo stopped a real flight segment");
            }
            // Looking back at the instrument really does hit its housing, rather than the distant wall.
            player.setPos(helper.absoluteVec(new Vec3(8.5, 4, 7.5)));
            float housing = ScopeRangefinder.measure(player.getEyePosition(), new Vec3(0, 0, 1),
                    new TargetCollisionSource(level,List.of(new net.minecraft.world.phys.AABB(base).inflate(64))), List.of(), 64, 65536, Long.MAX_VALUE);
            helper.assertTrue(housing > .49 && housing < 1.51, "Instrument's physical collision was removed: " + housing);
        }
        player.setPos(helper.absoluteVec(new Vec3(13, 4, 7.8)));
        helper.assertTrue(!TaczBridge.evaluate(player).eligible(), "Outside device range remained enabled");
        helper.assertTrue(level.getChunkSource().getLoadedChunksCount() == loaded, "Ray checks loaded terrain");
        helper.succeed();
    }

    @GameTest(template = "ballistics", batch = "rangefinder_collision")
    public static void allInstrumentStatesHaveExactStaticGeometry(GameTestHelper helper) {
        var block = ModContent.RANGEFINDER.get();
        var level = helper.getLevel();
        var base = helper.absolutePos(new BlockPos(8, 4, 8));
        level.setBlockAndUpdate(base.below(), Blocks.STONE.defaultBlockState());
        for (var facing : Direction.Plane.HORIZONTAL) for (var half : DoubleBlockHalf.values()) {
            var state = block.defaultBlockState().setValue(RangefinderBlock.FACING, facing).setValue(RangefinderBlock.HALF, half);
            var cell = StaticCollisionShapes.classify(state);
            helper.assertTrue(cell.kind() == CollisionCell.Kind.BOXES, "Instrument stayed conservative: " + state);
            var boxes = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
            helper.assertTrue(cell.boxes().equals(boxes.stream().map(b -> new CollisionCell.Box(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ)).toList()),
                    "Static geometry differs from real instrument collision");
            var pos = half == DoubleBlockHalf.LOWER ? base : base.above();
            // No neighbor updates: inspect each stored state independently, as the cache collector does.
            level.setBlock(pos, state, 2);
            SegmentTrace.Source localRay = (x,y,z) -> x == 8 && y == 4 && z == 8 ? cell : CollisionCell.AIR;
            helper.assertTrue(SegmentTrace.trace(localRay, 7.5, 4.5, 7.8, 4, 0, 0).end() == SegmentTrace.End.CLEAR,
                    "Cached instrument blocked an adjacent ray");
        }
        helper.assertTrue(StaticCollisionShapes.classify(com.tacz.guns.init.ModBlocks.GUN_SMITH_TABLE.get().defaultBlockState()).kind()
                == CollisionCell.Kind.CONSERVATIVE, "Unrelated custom block lost conservative protection");
        helper.succeed();
    }
}
