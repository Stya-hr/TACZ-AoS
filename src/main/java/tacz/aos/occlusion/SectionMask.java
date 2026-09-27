package tacz.aos.occlusion;
import java.util.*;
/** Immutable 16-cubed occupancy. Index is x + 16*z + 256*y. No client/world linkage. */
public record SectionMask(List<Long> solid, Map<Integer,Integer> plants) {
    public SectionMask {
        solid=List.copyOf(solid);plants=Map.copyOf(plants);
        if(solid.size()!=64 || plants.size()>4096)throw new IllegalArgumentException("Invalid section mask");
        for(int i:plants.keySet())if(i<0 || i>=4096 || (solid.get(i>>>6)&(1L<<(i&63)))!=0)throw new IllegalArgumentException("Invalid plant cell");
    }
    public boolean occupied(int x,int y,int z) {
        if((x|y|z)<0 || x>15 || y>15 || z>15)return false;
        int i=x+16*z+256*y;return (solid.get(i>>>6)&(1L<<(i&63)))!=0;
    }
    /** Greedy rectangles of exposed faces. Adjacent sections may duplicate boundary faces, never lose cover. */
    public List<float[]> faces() {
        var out=new ArrayList<float[]>();
        for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1})for(int slice=0;slice<16;slice++) {
            boolean[][] mask=new boolean[16][16];
            int u=(axis+1)%3,v=(axis+2)%3;
            for(int a=0;a<16;a++)for(int b=0;b<16;b++) {
                int x=axis==0?slice:axis==1?b:a;
                int y=axis==0?a:axis==1?slice:b;
                int z=axis==0?b:axis==1?a:slice;
                mask[a][b]=occupied(x,y,z)&&!occupied(x+(axis==0?sign:0),y+(axis==1?sign:0),z+(axis==2?sign:0));
            }
            for(int a=0;a<16;a++)for(int b=0;b<16;b++)if(mask[a][b]) {
                int w=1,h=1;while(a+w<16&&mask[a+w][b])w++;
                outer:while(b+h<16){for(int i=0;i<w;i++)if(!mask[a+i][b+h])break outer;h++;}
                for(int i=0;i<w;i++)for(int j=0;j<h;j++)mask[a+i][b+j]=false;
                float[] quad=new float[12];
                for(int i=0;i<4;i++){quad[i*3+axis]=slice+(sign>0?1:0);quad[i*3+u]=a+(i==1||i==2?w:0);quad[i*3+v]=b+(i>=2?h:0);}
                out.add(quad);
            }
        }
        return List.copyOf(out);
    }
}
