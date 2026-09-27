package tacz.aos.ballistics;

import java.util.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Per-flight transition from unrestricted transit to the first continuous target interval. */
public final class TargetFlightBoundary {
    public record Limit(double fraction, boolean stop) {}
    private record Interval(double enter,double exit) {}
    private boolean entered;
    public boolean entered(){return entered;}

    public Limit limit(Vec3 start,Vec3 movement,List<AABB> regions) {
        var intervals=new ArrayList<Interval>();
        for(var region:regions) {
            var hit=clip(start,movement,region);
            if(hit!=null)intervals.add(hit);
        }
        intervals.sort(Comparator.comparingDouble(Interval::enter));
        if(intervals.isEmpty())return new Limit(entered?0:1,entered);
        var first=intervals.get(0);
        // Previously entered flights may not bridge a gap or resume in a different region.
        if(entered&&first.enter>1e-12)return new Limit(0,true);
        entered=true;
        double exit=first.exit;
        for(int i=1;i<intervals.size();i++) {
            var next=intervals.get(i);
            if(next.enter>exit+1e-12)break;
            exit=Math.max(exit,next.exit);
        }
        return exit<=1?new Limit(Math.max(0,exit),true):new Limit(1,false);
    }

    private static Interval clip(Vec3 p,Vec3 d,AABB b) {
        double enter=0,exit=Double.POSITIVE_INFINITY;
        double[] start={p.x,p.y,p.z},move={d.x,d.y,d.z};
        double[] min={b.minX,b.minY,b.minZ},max={b.maxX,b.maxY,b.maxZ};
        for(int axis=0;axis<3;axis++) {
            if(move[axis]==0) {
                if(start[axis]<min[axis]||start[axis]>=max[axis])return null;
            } else {
                double a=(min[axis]-start[axis])/move[axis],c=(max[axis]-start[axis])/move[axis];
                enter=Math.max(enter,Math.min(a,c));exit=Math.min(exit,Math.max(a,c));
                if(exit<=enter)return null; // A tangent point does not enter a volume.
            }
        }
        return enter>1?null:new Interval(enter,exit);
    }
}
