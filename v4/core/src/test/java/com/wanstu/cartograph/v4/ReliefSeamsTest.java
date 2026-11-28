package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class ReliefSeamsTest {
    static void check(boolean value,String msg){if(!value)throw new AssertionError(msg);}
    static final FeatureDictionary.Feature LAND=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
    interface Height {int at(int x,int z);}
    static MapRaster raster(Key key,Height height){
        Map<Integer,Entry> values=new HashMap<>();long area=1L<<(2*key.lod());
        for(int z=0;z<32;z++)for(int x=0;x<32;x++){
            int h=height.at(x,z);if(h==Integer.MIN_VALUE)continue;
            Entry e=Entry.leaf(LAND,h,h,0,20000,20000,20000);
            if(key.lod()!=0){
                var a=e.stats();long[] hist=a.histogram();hist[FeatureDictionary.Terrain.ROCK.ordinal()]=area;
                e=new Entry(new Aggregate(area,area,area,h*area,h*area,20000*area,20000*area,20000*area,0,0,h,h,hist),null);
            }
            values.put(z*32+x,e);
        }
        return MapRaster.build(new WorldGeography.RenderPage(1,new Page(key,1,values)));
    }
    static ReliefSeams.Input exact(MapRaster r){return new ReliefSeams.Input(r.key(),new MapTileCoverage.Patch(r.key(),0,0,1,0,0,1),r);}
    static ReliefSeams.Input input(Key target,MapTileCoverage.Patch patch,MapRaster r){return new ReliefSeams.Input(target,patch,r);}
    public static void main(String[] args){
        var a=raster(new Key(0,-1,-1),(x,z)->80);
        var b=raster(new Key(0,0,-1),(x,z)->64);
        var pair=List.of(exact(a),exact(b));
        var walls=ReliefSeams.plan(pair,32768).walls();
        check(walls.size()==32,"exact same LOD seam");
        for(int i=0;i<32;i++){
            var w=walls.get(i);check(w.owner()==0&&w.slot()==i*32+31&&w.direction()==1&&w.x()==0&&w.z()==-32+i&&w.length()==1&&w.top()==80&&w.bottom()==64,"negative-world seam and higher texel");
        }
        var camera=new MapCamera(0,72,-20,4,90,45);
        var screen=camera.project(new MapCamera.World(0,72,-31.5));
        var picker=new MapRayPicker(camera,screen.x(),screen.y());
        for(var w:walls){
            double u=w.slot()%32+.5,v=w.slot()/32+.5;
            picker.face(pair.get(w.owner()).raster(),w.x(),w.top(),w.z(),w.x(),w.bottom(),w.z()+w.length(),u,v,u,v);
        }
        check(picker.result()!=null&&picker.result().side()&&Math.abs(picker.result().world().y()-72)<1e-6,"same seam geometry ray intersection");
        check(ReliefSeams.plan(List.of(exact(a)),32768).walls().isEmpty(),"no cliff to absent neighbor");
        var equal=raster(b.key(),(x,z)->80);
        check(ReliefSeams.plan(List.of(exact(a),exact(equal)),32768).walls().isEmpty(),"equal level no seam");
        var coarse=raster(new Key(1,-1,0),(x,z)->72);
        var fine=raster(new Key(0,0,0),(x,z)->z%2==0?90:60);
        var target=new Key(1,0,0);
        var finePatch=new MapTileCoverage.Patch(fine.key(),0,0,.5,0,0,1);
        var mixed=ReliefSeams.plan(List.of(exact(coarse),input(target,finePatch,fine)),32768).walls();
        check(mixed.size()==32,"split coarse edge at every fine texel");
        for(int i=0;i<32;i++){
            var w=mixed.get(i);
            check(w.x()==0&&w.z()==i&&w.length()==1&&w.owner()==(i%2==0?1:0)&&w.top()==(i%2==0?90:72)&&w.bottom()==(i%2==0?72:60),"mixed LOD ownership");
            check(w.slot()==(i%2==0?i*32:(i/2)*32+31),"mixed LOD texture");
        }
        var parent=raster(new Key(2,0,0),(x,z)->64);
        var patches=MapTileCoverage.resolve(target,k->k.equals(parent.key())||k.equals(fine.key())?MapTileCoverage.State.READY:MapTileCoverage.State.MISSING);
        List<ReliefSeams.Input> parts=new ArrayList<>();
        for(var p:patches)parts.add(input(target,p,p.source().equals(fine.key())?fine:parent));
        var inside=ReliefSeams.plan(parts,32768).walls();
        check(inside.size()==64,"child and clipped ancestor interior quadrant seams");
        for(var w:inside)check(w.x()==32||w.z()==32,"only true patch boundaries");
        var unknown=raster(fine.key(),(x,z)->z%2==0?90:Integer.MIN_VALUE);
        var gaps=ReliefSeams.plan(List.of(exact(coarse),input(target,finePatch,unknown)),32768).walls();
        check(gaps.size()==16&&gaps.stream().allMatch(w->w.z()%2==0),"unknown surface excluded");
        var capped=ReliefSeams.plan(pair,5);check(capped.limited()&&capped.walls().size()==5,"bounded output");
        // Sparse previews may have more patches than the seam work budget.
        // Retain actual terrain and return a bounded, valid partial seam plan.
        List<ReliefSeams.Input> oversized=new ArrayList<>(pair);
        var tiny=raster(new Key(0,0,0),(x,z)->64);
        for(int i=2;i<ReliefSeams.MAX_PATCHES+20;i++){
            var k=new Key(0,i,0);
            // Reuse the same raster, clipped in disjoint cells within its bounds.
            int x=i%32,z=i/32;
            oversized.add(input(k,new MapTileCoverage.Patch(tiny.key(),0,0,1,x/32.0,z/32.0,1/32.0),tiny));
        }
        var bounded=ReliefSeams.plan(oversized,32768);
        check(bounded.limited()&&bounded.walls().size()<=32768&&bounded.walls().stream().allMatch(w->w.owner()<ReliefSeams.MAX_PATCHES),"oversized preview bounded without throw");
        check(bounded.walls().stream().filter(w->w.x()==0&&w.z()<0).count()==32,"retained known primary seam");
        Random random=new Random(551);
        for(int iteration=0;iteration<100;iteration++){
            int[] left=new int[32],right=new int[32];
            for(int i=0;i<32;i++){left[i]=random.nextInt(10)+60;right[i]=random.nextInt(10)+60;}
            var l=raster(new Key(0,-1,0),(x,z)->left[z]);var r=raster(new Key(0,0,0),(x,z)->right[z]);
            var actual=ReliefSeams.plan(List.of(exact(l),exact(r)),32768).walls();
            int count=0;for(int i=0;i<32;i++)if(left[i]!=right[i]){
                var w=actual.get(count++);check(w.z()==i&&w.owner()==(left[i]>right[i]?0:1)&&w.top()==Math.max(left[i],right[i])&&w.bottom()==Math.min(left[i],right[i]),"independent seam oracle");
            }
            check(actual.size()==count,"no duplicate or false wall");
        }
        System.out.println("PASS: exact/mixed LOD seams, clipped ancestor quadrants, negative coordinates, missing surface, higher texel, 100 independent seam oracles and bounded output");
    }
}
