package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class MapLandformsTest {
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    static final FeatureDictionary.Feature LEAF=new FeatureDictionary.Feature("minecraft:oak_leaves[distance=3,persistent=false]","minecraft:plains",FeatureDictionary.Terrain.FOREST);
    static final FeatureDictionary.Feature GRASS=new FeatureDictionary.Feature("minecraft:grass_block","minecraft:forest",FeatureDictionary.Terrain.GRASS);
    static final FeatureDictionary.Feature PLANT=new FeatureDictionary.Feature("minecraft:wheat[age=7]","minecraft:forest",FeatureDictionary.Terrain.FOREST);
    static final FeatureDictionary.Feature WATER=new FeatureDictionary.Feature("minecraft:water","minecraft:ocean",FeatureDictionary.Terrain.WATER);
    interface Sample{Entry at(int x,int z);}
    static Entry entry(FeatureDictionary.Feature f,int ground,int surface){return Entry.leaf(f,surface,ground,f==WATER?3:0,0,30000,0);}
    static MapLandforms build(int lod,int px,int pz,Sample src){
        Map<Integer,Entry> m=new HashMap<>();
        for(int z=0;z<32;z++)for(int x=0;x<32;x++){
            Entry e=src.at(x,z);
            if(e!=null)m.put(z*32+x,e);
        }
        return MapRaster.build(new WorldGeography.RenderPage(1,new Page(new Key(lod,px,pz),1,m))).landforms();
    }
    static Entry coarse(Entry e){return new Entry(Aggregate.merge(List.of(e.stats(),e.stats(),e.stats(),e.stats())),null);}
    public static void main(String[] args){
        var canopy=build(0,-3,8,(x,z)->entry(LEAF,64,70));
        check(canopy.symbols().size()==16&&canopy.symbols().stream().allMatch(s->s.kind()==MapLandforms.Kind.CANOPY),"connected observed elevated leaves");
        check(canopy.symbols().equals(build(0,5,-2,(x,z)->entry(LEAF,64,70)).symbols()),"world coordinate invariance");
        check(build(0,0,0,(x,z)->entry(PLANT,64,70)).symbols().isEmpty(),"plant color and forest biome not proof");
        check(build(0,0,0,(x,z)->entry(GRASS,120,120)).symbols().isEmpty(),"high plateau not mountain");
        check(build(0,0,0,(x,z)->entry(LEAF,64,64)).symbols().isEmpty(),"ground-level leaves not canopy");
        check(build(0,0,0,(x,z)->entry((x+z)%2==0?LEAF:GRASS,64,70)).symbols().isEmpty(),"disconnected canopy");
        check(build(0,0,0,(x,z)->(x%8==0&&z%8==0)?null:entry(LEAF,64,70)).symbols().isEmpty(),"unknown footprint");
        check(build(0,0,0,(x,z)->entry(x%8==0?WATER:LEAF,64,70)).symbols().isEmpty(),"water footprint");
        check(build(1,0,0,(x,z)->coarse(entry(LEAF,64,70))).symbols().isEmpty(),"coarse plant statistics not canopy");
        check(build(0,0,0,(x,z)->entry(GRASS,64+x*5,64+x*5)).symbols().isEmpty(),"monotone slope not peak");
        var hills=build(0,0,0,(x,z)->{int h=(x%8==3&&z%8==3)?72:64;return entry(GRASS,h,h);});
        check(hills.symbols().size()==16&&hills.symbols().stream().allMatch(s->s.kind()==MapLandforms.Kind.HILL),"observed local hill");
        var peaks=build(1,0,0,(x,z)->{int h=(x%8==3&&z%8==3)?104:64;return coarse(entry(GRASS,h,h));});
        check(peaks.symbols().size()==16&&peaks.symbols().stream().allMatch(s->s.kind()==MapLandforms.Kind.PEAK),"observed coarse local relief");
        check(Long.bitCount(MapLandforms.largestComponent((1L<<7)|(1L<<8)))==1,"no wrap across rows");
        Random rnd=new Random(651);
        for(int i=0;i<1000;i++){
            long mask=rnd.nextLong(),remaining=mask;int best=0;
            while(remaining!=0){
                var queue=new ArrayDeque<Integer>();int start=Long.numberOfTrailingZeros(remaining);queue.add(start);remaining&=~(1L<<start);int count=0;
                while(!queue.isEmpty()){
                    int v=queue.remove();count++;
                    for(int n:new int[]{v%8==0?-1:v-1,v%8==7?-1:v+1,v-8,v+8})
                        if(n>=0&&n<64&&(remaining&(1L<<n))!=0){queue.add(n);remaining&=~(1L<<n);}
                }
                best=Math.max(best,count);
            }
            check(Long.bitCount(MapLandforms.largestComponent(mask))==best,"connected component oracle");
        }
        for(var page:List.of(canopy,hills,peaks))for(var s:page.symbols())for(var style:MapRaster.Style.values()){
            int[] lines={0};
            MapLandformGlyphs.emit(s,style,(x0,z0,x1,z1,c)->{
                check(x0>=s.blockX()&&x1>=s.blockX()&&x0<s.blockX()+8&&x1<s.blockX()+8&&
                    z0>=s.blockZ()&&z1>=s.blockZ()&&z0<s.blockZ()+8&&z1<s.blockZ()+8,"glyph within observed footprint");
                lines[0]++;
            });
            check(style.ordinal()<2?lines[0]==0:lines[0]>0&&lines[0]<=10,"bounded hand-only glyph");
        }
        List<MapLandforms> pages=new ArrayList<>();
        for(int i=0;i<40;i++)pages.add(build(0,i,0,(x,z)->entry(LEAF,64,70)));
        var plan=MapLandforms.plan(pages,512);
        check(plan.size()==512&&plan.subList(0,40).stream().map(MapLandforms.Placed::key).distinct().count()==40,"fair bounded plan");
        System.out.println("PASS: observed canopy and relief, 1000 component oracles, unknown/mixed/biome exclusion, glyph containment and fair capped plan");
    }
}
