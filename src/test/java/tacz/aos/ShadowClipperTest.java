package tacz.aos;
import org.junit.jupiter.api.Test;
import java.util.*;
import tacz.aos.occlusion.*;
import static org.junit.jupiter.api.Assertions.*;
class ShadowClipperTest {
    private static ShadowClipper.Vertex v(double x,double y,double z){return new ShadowClipper.Vertex(x,y,z,new float[]{(float)x,(float)y});}
    private static List<ShadowClipper.Vertex> panel(double z){return List.of(v(-1,-1,z),v(1,-1,z),v(1,1,z),v(-1,1,z));}
    private static List<List<ShadowClipper.Vertex>> clip(OccluderBox box,double z){return ShadowClipper.clip(panel(z),List.of(ShadowClipper.shadow(box)),new ShadowClipper.Budget(10000));}
    private static double area(List<List<ShadowClipper.Vertex>> polygons) {
        double result=0;for(var p:polygons){double a=0;for(int i=0;i<p.size();i++){var x=p.get(i);var y=p.get((i+1)%p.size());a+=x.x()*y.y()-x.y()*y.x();}result+=Math.abs(a)/2;}return result;
    }
    @Test void wallClipsOnlyLowerHalfAndRemovalRestoresIt() {
        var wall=new OccluderBox(-2,-2,4,2,0,5);var pieces=clip(wall,10);
        assertEquals(2,area(pieces),1e-6);
        for(var p:pieces)for(var v:p){assertTrue(v.y()>=-1e-6);assertEquals(v.x(),v.attributes()[0],1e-6);assertEquals(v.y(),v.attributes()[1],1e-6);}
        assertEquals(4,area(ShadowClipper.clip(panel(10),List.of(),new ShadowClipper.Budget(10))),1e-6);
    }
    @Test void fullCoverAndCoverBehindTargetDoNotBecomeWholeEntityVisibilityApproximations() {
        assertTrue(clip(new OccluderBox(-2,-2,4,2,2,5),10).isEmpty());
        assertEquals(4,area(clip(new OccluderBox(-2,-2,14,2,2,15),10)),1e-6);
        assertEquals(4,area(clip(new OccluderBox(3,-2,4,4,2,5),10)),1e-6);
        assertTrue(ShadowClipper.shadow(new OccluderBox(-1,-1,-1,1,1,1)).isEmpty());
    }
    @Test void narrowPostCutsInteriorEvenWhenAllModelCornersAreVisible() {
        var pieces=clip(new OccluderBox(-.1,-2,4,.1,2,5),10);
        assertEquals(3,area(pieces),1e-6);
        assertTrue(pieces.size()>=2);
    }
    @Test void overlappingOccludersAndBudgetExhaustionAreBounded() {
        var walls=List.of(ShadowClipper.shadow(new OccluderBox(-2,-2,4,0,2,5)),ShadowClipper.shadow(new OccluderBox(-1,-2,4,2,0,5)));
        assertEquals(1,area(ShadowClipper.clip(panel(10),walls,new ShadowClipper.Budget(10000))),1e-6);
        assertEquals(4,area(ShadowClipper.clip(panel(10),walls,new ShadowClipper.Budget(0))),1e-6);
    }
    @Test void shadowHalfspacesAgreeWithIndependentRayBoxIntersection() {
        var r=new Random(51);
        for(int i=0;i<10000;i++) {
            double x=r.nextDouble()*20-10,y=r.nextDouble()*20-10,z=r.nextDouble()*20-10;
            var b=new OccluderBox(x,y,z,x+1,y+2,z+1);
            if(x<=0&&x+1>=0&&y<=0&&y+2>=0&&z<=0&&z+1>=0)continue;
            var p=v(r.nextDouble()*40-20,r.nextDouble()*40-20,r.nextDouble()*40-20);
            boolean shadow=ShadowClipper.shadow(b).stream().allMatch(a->a.at(p)>=0);
            double lo=0,hi=1;
            double[] mn={x,y,z},mx={x+1,y+2,z+1},dir={p.x(),p.y(),p.z()};
            for(int j=0;j<3;j++){double a=mn[j]/dir[j],c=mx[j]/dir[j];lo=Math.max(lo,Math.min(a,c));hi=Math.min(hi,Math.max(a,c));}
            assertEquals(lo<=hi,shadow,"Box cone differs from ray geometry");
        }
    }
}
