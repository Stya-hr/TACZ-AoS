package tacz.longrangesniping;
import org.junit.jupiter.api.Test;
import tacz.longrangesniping.terrain.ChunkLine;
import tacz.longrangesniping.scoping.AimStability;
import static org.junit.jupiter.api.Assertions.*;
class TargetingTest {
    private long key(int x,int z){return (x&0xffffffffL)|((z&0xffffffffL)<<32);}
    @Test void axisAndNegativeCoordinates(){assertEquals(129,ChunkLine.columns(8,8,2056,8).size());assertEquals(java.util.Set.of(key(-2,-1),key(-1,-1)),ChunkLine.columns(-24,-8,-8,-8));}
    @Test void cornerIncludesBothTouchedNeighbors(){var s=ChunkLine.columns(8,8,24,24);assertEquals(java.util.Set.of(key(0,0),key(1,0),key(0,1),key(1,1)),s);}
    @Test void exactEdgeCannotHideAnUnknownColumn(){var s=ChunkLine.columns(16,8,16,40);assertTrue(s.contains(key(0,1)));assertTrue(s.contains(key(1,1)));}
    @Test void invalidLinesFailBoundedly(){assertThrows(IllegalArgumentException.class,()->ChunkLine.columns(Double.NaN,0,0,0));assertThrows(IllegalArgumentException.class,()->ChunkLine.columns(0,0,40000,0));}
    @Test void stationaryAimActivatesWithoutMouseEvents(){var s=new AimStability();boolean ready=false;for(int t=0;t<=5;t++)ready=s.update(1,0,0,t,10,3);assertTrue(ready);assertFalse(s.update(0,0,1,6,10,3));for(int t=7;t<30;t++)ready=s.update(0,0,1,t,10,3);assertTrue(ready);}
    @Test void sweepingNeverBecomesStable(){var s=new AimStability();for(int t=0;t<40;t++){double a=Math.toRadians(t*10);assertFalse(s.update(Math.cos(a),0,Math.sin(a),t,10,3));}}
}
