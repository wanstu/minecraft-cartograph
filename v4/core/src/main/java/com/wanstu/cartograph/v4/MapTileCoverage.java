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
    /** Sparse index: only branches leading to actual resident observations. */
    public static Set<Key> branches(Collection<Key> resident){
        Set<Key> out=new HashSet<>();
        for(Key k:resident)for(Key p=parent(k);p!=null;p=parent(p))
            if(!out.add(p))break;
        return Set.copyOf(out);
    }
    /** Nearest available ancestor, clipped to target; unknown exact pages never use fallback. */
    public static List<Patch> resolve(Key target,Lookup lookup){
        return resolve(target,lookup,Set.of());
    }
    /** Reuse arbitrarily finer resident observations on zoom-out, without scanning
     * absent subtrees or overlapping a coarser preview. EMPTY always wins. */
    public static List<Patch> resolve(Key target,Lookup lookup,Set<Key> branches){
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
        List<Patch> out=new ArrayList<>();
        collect(target,lookup,branches,coarse,0,0,1,true,out);
        return List.copyOf(out);
    }
    /** Return whether the subtree has explicit coverage/empty evidence. Otherwise
     * merge its unused quadrants back into one coarse patch. */
    private static boolean collect(Key k,Lookup lookup,Set<Key> branches,Patch coarse,
                                   double x,double z,double size,boolean root,List<Patch> out){
        State state=root?State.MISSING:lookup.state(k);
        if(state==State.EMPTY)return true;
        if(state==State.READY){out.add(new Patch(k,x,z,size,0,0,1));return true;}
        if(k.lod()>0&&(root||branches.contains(k))){
            int start=out.size();boolean any=false;double half=size*.5;
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                Key child=new Key(k.lod()-1,Math.addExact(Math.multiplyExact(k.x(),2),dx),
                    Math.addExact(Math.multiplyExact(k.z(),2),dz));
                any|=collect(child,lookup,branches,coarse,x+dx*half,z+dz*half,half,false,out);
            }
            if(any)return true;
            out.subList(start,out.size()).clear();
        }
        if(coarse!=null)out.add(new Patch(coarse.source,x,z,size,
            coarse.u+x*coarse.uvSize,coarse.v+z*coarse.uvSize,coarse.uvSize*size));
        return false;
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
