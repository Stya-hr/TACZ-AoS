package tacz.aos.occlusion;

import java.util.*;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.*;

/** The same visual-space coverage test on both sides; no world reads or movement extrapolation. */
public final class OcclusionCoverage {
    private OcclusionCoverage() {}
    public static List<Long> required(Vec3 eye,AABB entity,int radius,int minSection,int maxSection) {
        return sections(eye,entity,radius,minSection,maxSection,.5,2);
    }
    public static List<Long> prefetch(Vec3 eye,AABB entity,int radius,int minSection,int maxSection,double margin) {
        return sections(eye,entity,radius,minSection,maxSection,margin,2+(int)Math.ceil(margin/16));
    }
    private static List<Long> sections(Vec3 eye,AABB entity,int radius,int minSection,int maxSection,double margin,int chunks) {
        var bounds=entity.inflate(margin);var center=bounds.getCenter();
        var end=center.add(eye.subtract(center).normalize().scale(Math.min(radius,eye.distanceTo(center))));
        var area=bounds.minmax(bounds.move(end.subtract(center)));
        int cx=((int)Math.floor(center.x))>>4,cz=((int)Math.floor(center.z))>>4;
        var result=new ArrayList<Long>();double pad=Math.max(bounds.getXsize(),Math.max(bounds.getYsize(),bounds.getZsize()))/2+.5;
        for(int x=Math.max(cx-chunks,((int)Math.floor(area.minX))>>4);x<=Math.min(cx+chunks,((int)Math.floor(area.maxX))>>4);x++)
            for(int y=Math.max(minSection,((int)Math.floor(area.minY))>>4);y<=Math.min(maxSection-1,((int)Math.floor(area.maxY))>>4);y++)
                for(int z=Math.max(cz-chunks,((int)Math.floor(area.minZ))>>4);z<=Math.min(cz+chunks,((int)Math.floor(area.maxZ))>>4);z++) {
                    var box=new AABB(x*16,y*16,z*16,x*16+16,y*16+16,z*16+16).inflate(pad);
                    if(box.contains(center)||box.clip(center,end).isPresent())result.add(SectionPos.asLong(x,y,z));
                }
        return result;
    }
}
