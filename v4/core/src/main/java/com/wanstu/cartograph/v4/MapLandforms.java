package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** Conservative page-local cartographic symbols. No biome-based forest inference or seed prediction. */
public final class MapLandforms {
    public enum Kind { CANOPY, HILL, PEAK }
    /** Anchor and footprint in local page cells, not fabricated objects in the world. */
    public record Symbol(Kind kind,int x,int z,int blockX,int blockZ,int strength){}
    private static final Set<String> LEAVES=Set.of("minecraft:oak_leaves","minecraft:spruce_leaves","minecraft:birch_leaves",
        "minecraft:jungle_leaves","minecraft:acacia_leaves","minecraft:dark_oak_leaves","minecraft:mangrove_leaves",
        "minecraft:azalea_leaves","minecraft:flowering_azalea_leaves","minecraft:cherry_leaves");
    private static final long LEFT=0x0101010101010101L,RIGHT=0x8080808080808080L;
    public static final int MAX_SYMBOLS_PER_PAGE=16,MAX_DRAW_SYMBOLS=512;
    private final Key key;
    private final List<Symbol> symbols;
    private MapLandforms(Key key,List<Symbol> symbols){this.key=key;this.symbols=List.copyOf(symbols);}
    public Key key(){return key;}
    public List<Symbol> symbols(){return symbols;}
    static boolean canopy(Entry e){
        if(e==null||e.feature()==null)return false;
        var a=e.stats();
        if(a.groundCount()!=1||a.surfaceCount()!=1||a.surfaceSum()-a.groundSum()<2)return false;
        String material=e.feature().materialKey();int at=material.indexOf('[');
        return LEAVES.contains(at<0?material:material.substring(0,at));
    }
    static MapLandforms build(Page page,float[] ground,BitSet completeGround,byte[] dry){
        if(page.size()==0)return new MapLandforms(page.key(),List.of());
        List<Symbol> symbols=new ArrayList<>(MAX_SYMBOLS_PER_PAGE);
        for(int bz=0;bz<32;bz+=8)for(int bx=0;bx<32;bx+=8){
            boolean known=true;long leaves=0;float min=Float.POSITIVE_INFINITY,maxEdge=Float.NEGATIVE_INFINITY,peak=Float.NEGATIVE_INFINITY;
            int px=-1,pz=-1;
            for(int z=0;z<8&&known;z++)for(int x=0;x<8;x++){
                int slot=(bz+z)*32+bx+x;
                if(dry[slot]!=MapShorelines.LAND||!completeGround.get(slot)||!Float.isFinite(ground[slot])){known=false;break;}
                float h=ground[slot];min=Math.min(min,h);
                if(x<2||x>5||z<2||z>5)maxEdge=Math.max(maxEdge,h);
                else if(h>peak){peak=h;px=x;pz=z;}
                if(page.key().lod()==0&&canopy(page.entryAt(slot)))leaves|=1L<<(z*8+x);
            }
            if(!known)continue;
            // Prefer a prominent measured peak; a high flat plateau or monotone slope is not a peak.
            double relief=peak-min,prominence=peak-maxEdge;
            if(relief>=5&&prominence>=2){
                Kind kind=relief>=24&&prominence>=8?Kind.PEAK:Kind.HILL;
                symbols.add(new Symbol(kind,bx+px,bz+pz,bx,bz,(int)Math.min(255,relief)));
                continue;
            }
            // At coarse LOD the existing histogram cannot distinguish leaves from other plants.
            // Only a connected patch of actual elevated vanilla leaves earns a canopy symbol.
            long largest=largestComponent(leaves);
            if(Long.bitCount(largest)<24)continue;
            int anchor=-1,best=Integer.MAX_VALUE;
            for(int z=2;z<=5;z++)for(int x=2;x<=5;x++){
                int i=z*8+x;if((largest&(1L<<i))==0)continue;
                int d=(2*x-7)*(2*x-7)+(2*z-7)*(2*z-7);
                if(d<best){best=d;anchor=i;}
            }
            if(anchor>=0)symbols.add(new Symbol(Kind.CANOPY,bx+anchor%8,bz+anchor/8,bx,bz,Long.bitCount(largest)));
        }
        return new MapLandforms(page.key(),symbols);
    }
    static long largestComponent(long leaves){
        long best=0;
        while(leaves!=0){
            long component=Long.lowestOneBit(leaves),previous;
            do{
                previous=component;
                component|=leaves&(((component&~LEFT)>>>1)|((component&~RIGHT)<<1)|(component<<8)|(component>>>8));
            }while(component!=previous);
            if(Long.bitCount(component)>Long.bitCount(best))best=component;
            leaves&=~component;
        }
        return best;
    }
    /** Bounded deterministic selection. Fairly distribute symbols across pages before filling denser pages. */
    public record Placed(Key key,Symbol symbol){}
    public static List<Placed> plan(List<MapLandforms> pages,int cap){
        if(cap<0||cap>MAX_DRAW_SYMBOLS||pages.size()>384)throw new IllegalArgumentException("Landform budget");
        List<Placed> out=new ArrayList<>(Math.min(cap,pages.size()*2));
        for(int i=0;i<MAX_SYMBOLS_PER_PAGE&&out.size()<cap;i++)for(var page:pages){
            if(i<page.symbols.size())out.add(new Placed(page.key,page.symbols.get(i)));
            if(out.size()>=cap)break;
        }
        return List.copyOf(out);
    }
}
