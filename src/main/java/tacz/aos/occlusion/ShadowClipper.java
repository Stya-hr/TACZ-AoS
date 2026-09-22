package tacz.aos.occlusion;

import java.util.*;

/** Subtract convex box shadows from model polygons. No world access, GPU readback, or shader overrides. */
public final class ShadowClipper {
    private static final double EPS=1e-7;
    public record Plane(double x,double y,double z,double w) {
        public double at(Vertex v){return x*v.x+y*v.y+z*v.z+w;}
    }
    public record Vertex(double x,double y,double z,float[] attributes) {
        public Vertex mix(Vertex b,double t) {
            var a=new float[attributes.length];
            for(int i=0;i<a.length;i++)a[i]=(float)(attributes[i]+(b.attributes[i]-attributes[i])*t);
            return new Vertex(x+(b.x-x)*t,y+(b.y-y)*t,z+(b.z-z)*t,a);
        }
    }
    public static final class Budget {
        private int remaining;
        public Budget(int count){remaining=count;}
        boolean take(int n){remaining-=n;return remaining>=0;}
        public int remaining(){return Math.max(0,remaining);}
    }
    private ShadowClipper() {}
    /** Box coordinates relative to the eye. Interior halfspaces have positive signs. */
    public static List<Plane> shadow(OccluderBox b) {
        if(b.minX()<=0 && b.maxX()>=0 && b.minY()<=0 && b.maxY()>=0 && b.minZ()<=0 && b.maxZ()>=0)return List.of();
        var planes=new ArrayList<Plane>();
        if(b.minX()>0)planes.add(new Plane(1,0,0,-b.minX()));
        if(b.maxX()<0)planes.add(new Plane(-1,0,0,b.maxX()));
        if(b.minY()>0)planes.add(new Plane(0,1,0,-b.minY()));
        if(b.maxY()<0)planes.add(new Plane(0,-1,0,b.maxY()));
        if(b.minZ()>0)planes.add(new Plane(0,0,1,-b.minZ()));
        if(b.maxZ()<0)planes.add(new Plane(0,0,-1,b.maxZ()));
        var corners=new Vertex[8];
        for(int i=0;i<8;i++)corners[i]=new Vertex((i&1)==0?b.minX():b.maxX(),(i&2)==0?b.minY():b.maxY(),(i&4)==0?b.minZ():b.maxZ(),new float[0]);
        for(int i=0;i<8;i++)for(int bit=1;bit<=4;bit*=2) {
            int j=i^bit;if(j<i)continue;var a=corners[i];var c=corners[j];
            double x=a.y*c.z-a.z*c.y,y=a.z*c.x-a.x*c.z,z=a.x*c.y-a.y*c.x;
            double length=Math.sqrt(x*x+y*y+z*z);if(length<EPS)continue;
            var p=new Plane(x/length,y/length,z/length,0);double min=0,max=0;
            for(var v:corners){double d=p.at(v);min=Math.min(min,d);max=Math.max(max,d);}
            if(min<-EPS && max>EPS)continue;
            if(max<=EPS)p=new Plane(-p.x,-p.y,-p.z,0);
            var candidate=p;
            if(planes.stream().noneMatch(q->Math.abs(q.x-candidate.x)+Math.abs(q.y-candidate.y)+Math.abs(q.z-candidate.z)+Math.abs(q.w-candidate.w)<EPS))planes.add(p);
        }
        return List.copyOf(planes);
    }
    /** On exhaustion preserve completed shadows; only unchecked cover fails visible. */
    public static List<List<Vertex>> clip(List<Vertex> polygon,List<List<Plane>> shadows,Budget budget) {
        List<List<Vertex>> visible=List.of(polygon);
        for(var shadow:shadows) {
            if(shadow.isEmpty())continue;
            var next=new ArrayList<List<Vertex>>();
            for(var input:visible) {
                // Most primitives are entirely outside a shadow, or fully hidden, without any new vertices.
                boolean outside=false,inside=true;
                for(var plane:shadow) {
                    if(!budget.take(input.size()))return visible;
                    double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
                    for(var v:input){double d=plane.at(v);min=Math.min(min,d);max=Math.max(max,d);}
                    if(max<-EPS){outside=true;break;}
                    if(min<-EPS)inside=false;
                }
                if(outside){next.add(input);continue;}if(inside)continue;
                var remainder=input;
                for(var plane:shadow) {
                    if(!budget.take(remainder.size()))return visible;
                    var in=new ArrayList<Vertex>();var out=new ArrayList<Vertex>();
                    for(int i=0;i<remainder.size();i++) {
                        var a=remainder.get(i);var b=remainder.get((i+1)%remainder.size());
                        double da=plane.at(a),db=plane.at(b);boolean ai=da>=0,bi=db>=0;
                        (ai?in:out).add(a);
                        if(ai!=bi){var intersection=a.mix(b,da/(da-db));in.add(intersection);out.add(intersection);}
                    }
                    if(out.size()>=3)next.add(out);
                    if(in.size()<3)break;
                    remainder=in;
                }
                if(next.size()>32)return visible;
            }
            visible=next;if(visible.isEmpty())break;
        }
        return visible;
    }
}
