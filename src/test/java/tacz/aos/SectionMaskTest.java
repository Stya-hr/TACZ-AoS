package tacz.aos;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tacz.aos.occlusion.SectionMask;
class SectionMaskTest {
    @Test void emptyAndFullSectionMesh(){assertTrue(new SectionMask(Collections.nCopies(64,0L),Map.of()).faces().isEmpty());assertEquals(6,new SectionMask(Collections.nCopies(64,-1L),Map.of()).faces().size());}
    @Test void isolatedCubeAndSeparatedCubes(){var bits=new ArrayList<>(Collections.nCopies(64,0L));bits.set(0,1L);var one=new SectionMask(bits,Map.of());assertEquals(6,one.faces().size());assertTrue(one.occupied(0,0,0));assertFalse(one.occupied(0,1,0));bits.set(0,5L);assertEquals(12,new SectionMask(bits,Map.of()).faces().size());}
    @Test void denseCoverDoesNotHaveOldThirtyTwoBoxLimit(){var bits=new ArrayList<>(Collections.nCopies(64,0L));for(int x=0;x<16;x+=2)for(int y=0;y<16;y+=2)for(int z=0;z<16;z+=2){int i=x+16*z+256*y;bits.set(i>>>6,bits.get(i>>>6)|(1L<<(i&63)));}assertEquals(512*6,new SectionMask(bits,Map.of()).faces().size());}
    @Test void rejectsOverlappingOrUnboundedPlants(){assertThrows(IllegalArgumentException.class,()->new SectionMask(Collections.nCopies(64,-1L),Map.of(0,1)));assertThrows(IllegalArgumentException.class,()->new SectionMask(Collections.nCopies(64,0L),Map.of(4096,1)));}
}
