package tacz.aos.test;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import tacz.aos.config.ServerConfig;
import tacz.aos.scoping.ScopeActivity;
import tacz.aos.scoping.ScopeSubscriptions;

/** Establish real simulation tickets before exercising observation, rather than injecting render leases. */
final class ObservationFixture {
    static void ready(GameTestHelper h, ServerPlayer player, Vec3 target, Runnable check) {
        var activity=ScopeActivity.INSTANCE;
        ScopeRetentionGameTests.register(player,true);
        activity.releaseAll();
        activity.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.START,()->true,h.getLevel().getServer()));
        int radius=ServerConfig.current().activity().radius()+3;
        int cx=(int)Math.floor(target.x/16),cz=(int)Math.floor(target.z/16);
        for(int x=cx-radius;x<=cx+radius;x++)for(int z=cz-radius;z<=cz+radius;z++)h.getLevel().getChunk(x,z);
        activity.consider(player,target);
        h.runAfterDelay(15,()->{
            try {
                h.assertTrue(!activity.observationBounds(player).isEmpty(),"Fixture failed to acquire observation core");
                check.run();
            } finally {
                ScopeSubscriptions.INSTANCE.replace(player,java.util.List.of());
                activity.releaseAll();
                ScopeRetentionGameTests.register(player,false);
            }
        });
    }
}
