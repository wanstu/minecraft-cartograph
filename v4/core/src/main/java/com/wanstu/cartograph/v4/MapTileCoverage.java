package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Presentation-only multiresolution fallback. Bounds and UVs are in target-page fractions. */
public final class MapTileCoverage {
    private MapTileCoverage(){}
    public enum State { MISSING, EMPTY, READY }
    @FunctionalInterface public interface Lookup { State state(Key key); }
    public record Patch(Key source,double x,double z,double size,double u,double v,double uvSize){}
    public static Key parent(Key key){
        if(key.lod()==10)return null;
        return new Key(key.lod()+1,Math.floorDiv(key.x(),2),Math.floorDiv(key.z(),2));
    }
    /** Nearest available ancestor, clipped to target; unknown exact pages never use fallback. */
    public static List<Patch> resolve(Key target,Lookup lookup){
        State exact=lookup.state(target);
        if(exact==State.EMPTY)return List.of();
        if(exact==State.READY)return List.of(new Patch(target,0,0,1,0,0,1));
        Patch coarse=null;
        for(Key ancestor=parent(target);ancestor!=null;ancestor=parent(ancestor)){
            State state=lookup.state(ancestor);
            if(state==State.EMPTY)break;
            if(state==State.READY){
                int factor=1<<(ancestor.lod()-target.lod());
                double size=1.0/factor;
                coarse=new Patch(ancestor,0,0,1,Math.floorMod(target.x(),factor)*size,Math.floorMod(target.z(),factor)*size,size);
                break;
            }
        }
        // During zoom-out retain already uploaded immediate children in their respective quadrants.
        // No overlap: the parent is clipped to only the missing quadrants.
        if(target.lod()==0)return coarse==null?List.of():List.of(coarse);
        List<Patch> out=new ArrayList<>(4);
        boolean anyChild=false;
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
            Key child=new Key(target.lod()-1,Math.addExact(Math.multiplyExact(target.x(),2),dx),
                Math.addExact(Math.multiplyExact(target.z(),2),dz));
            State state=lookup.state(child);
            double x=dx*.5,z=dz*.5;
            if(state==State.READY){out.add(new Patch(child,x,z,.5,0,0,1));anyChild=true;}
            else if(state==State.EMPTY)anyChild=true;
            else if(coarse!=null)out.add(new Patch(coarse.source,x,z,.5,
                coarse.u+x*coarse.uvSize,coarse.v+z*coarse.uvSize,coarse.uvSize*.5));
        }
        if(!anyChild&&coarse!=null)return List.of(coarse);
        return List.copyOf(out);
    }
    /** Visible pages first, with their immediate parent interleaved; then a one-page neighbor ring.
     * The ring is only a residency hint, not a terrain collection boundary.
     */
    public static List<Key> requests(List<Key> visible,int limit){return requests(visible,limit,true);}
    public static List<Key> requests(List<Key> visible,int limit,boolean neighbors){
        if(visible.size()>limit)throw new IllegalArgumentException("Request budget");
        LinkedHashSet<Key> out=new LinkedHashSet<>();
        // Reserve enough space for every exact page, even at high viewport resolution.
        int parents=Math.max(0,limit-visible.size());
        Set<Key> inserted=new HashSet<>();
        for(Key k:visible){
            Key p=parent(k);
            if(p!=null&&inserted.size()<parents&&inserted.add(p))out.add(p);
            out.add(k);
        }
        if(!neighbors)return List.copyOf(out);
        for(Key k:visible){
            if(out.size()>=limit)break;
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                if(out.size()>=limit)break;
                if(dx==0&&dz==0)continue;
                out.add(new Key(k.lod(),Math.addExact(k.x(),dx),Math.addExact(k.z(),dz)));
            }
        }
        return List.copyOf(out);
    }
}
