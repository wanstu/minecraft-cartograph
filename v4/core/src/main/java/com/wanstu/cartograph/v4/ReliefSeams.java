package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Join only actually drawn heightfield patches. Edge coordinates are integral world blocks. */
public final class ReliefSeams {
    private ReliefSeams(){}
    public static final int MAX_PATCHES=256,MAX_WALLS=32768;
    public record Input(Key target,MapTileCoverage.Patch patch,MapRaster raster){
        public Input{
            Objects.requireNonNull(target);Objects.requireNonNull(patch);Objects.requireNonNull(raster);
            if(!patch.source().equals(raster.key()))throw new IllegalArgumentException("Raster mismatch");
        }
    }
    /** Source index and texel of the higher side; coordinates in world space. */
    public record Wall(int owner,int slot,int direction,int x,int z,int length,float top,float bottom){}
    public record Result(List<Wall> walls,boolean limited){}
    private record Edge(int owner,int slot,int direction,int start,int end,float y){}
    private record Boundary(boolean vertical,int coordinate){}
    private static final class Sides {
        final List<Edge> negative=new ArrayList<>(),positive=new ArrayList<>();
    }
    public static Result plan(List<Input> inputs,int limit){
        if(limit<0||limit>MAX_WALLS)throw new IllegalArgumentException("Seam budget");
        // Sparse multi-level previews can exceed the legacy patch count. Bound
        // seam planning without throwing or dropping terrain top surfaces.
        int count=Math.min(inputs.size(),MAX_PATCHES);
        boolean limited=inputs.size()>count;
        // Sorted boundaries and intervals ensure deterministic ownership, no all-pairs scans.
        var boundaries=new TreeMap<Boundary,Sides>(Comparator.comparing(Boundary::vertical).thenComparingInt(Boundary::coordinate));
        for(int i=0;i<count;i++)addEdges(boundaries,inputs.get(i),i);
        List<Wall> walls=new ArrayList<>();
        for(var pair:boundaries.entrySet()){
            var sides=pair.getValue();
            sides.negative.sort(Comparator.comparingInt(Edge::start));
            sides.positive.sort(Comparator.comparingInt(Edge::start));
            int a=0,b=0;
            while(a<sides.negative.size()&&b<sides.positive.size()){
                Edge left=sides.negative.get(a),right=sides.positive.get(b);
                int lo=Math.max(left.start,right.start),hi=Math.min(left.end,right.end);
                if(lo<hi&&left.y!=right.y&&left.owner!=right.owner){
                    if(walls.size()==limit)return new Result(List.copyOf(walls),true);
                    Edge high=left.y>right.y?left:right;
                    int c=pair.getKey().coordinate;
                    walls.add(new Wall(high.owner,high.slot,high.direction,pair.getKey().vertical?c:lo,
                        pair.getKey().vertical?lo:c,hi-lo,Math.max(left.y,right.y),Math.min(left.y,right.y)));
                }
                if(left.end<=right.end)a++;
                if(right.end<=left.end)b++;
            }
        }
        return new Result(List.copyOf(walls),limited);
    }
    private static void addEdges(Map<Boundary,Sides> map,Input in,int index){
        var p=in.patch;var r=in.raster;int cell=1<<r.key().lod(),size=32*cell;
        int bx=Math.multiplyExact(r.key().x(),size),bz=Math.multiplyExact(r.key().z(),size);
        int x0=Math.addExact(bx,(int)Math.round(p.u()*size)),z0=Math.addExact(bz,(int)Math.round(p.v()*size));
        int extent=(int)Math.round(p.uvSize()*size),x1=Math.addExact(x0,extent),z1=Math.addExact(z0,extent);
        if(extent<=0||x0<bx||z0<bz||x1>bx+size||z1>bz+size)throw new IllegalArgumentException("Patch bounds");
        for(int dir=0;dir<4;dir++){
            boolean vertical=dir<2;
            int fixed=dir==0?x0:dir==1?x1:dir==2?z0:z1;
            int start=vertical?z0:x0,end=vertical?z1:x1,origin=vertical?bz:bx;
            int across=Math.floorDiv(fixed-(vertical?bx:bz)-(dir%2==1?1:0),cell);
            for(int pos=start;pos<end;){
                int along=Math.floorDiv(pos-origin,cell),next=Math.min(end,origin+(along+1)*cell);
                int slot=vertical?along*32+across:across*32+along;
                float y=r.surface(slot);
                // A partially observed cell is rendered as a preview but cannot establish a complete seam.
                if(r.completeSurface(slot)&&Float.isFinite(y)){
                    var sides=map.computeIfAbsent(new Boundary(vertical,fixed),k->new Sides());
                    (dir%2==1?sides.negative:sides.positive).add(new Edge(index,slot,dir,pos,next,y));
                }
                pos=next;
            }
        }
    }
}
