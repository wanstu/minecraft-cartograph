package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Exact observed water/land contacts, not a classification of oceans, rivers or lakes.
 * Page-local runs are generated once on the geography worker. Border runs only
 * exist when a matching, fully observed neighbor is supplied.
 */
public final class MapShorelines {
    private static final int EDGE=32;
    public static final byte UNKNOWN=0,LAND=1,WATER=2;
    public static final int MAX_SEGMENTS=4096;
    /** Integer grid-edge endpoints, in cells relative to the owning page. */
    public record Run(int x0,int z0,int x1,int z1){}
    public record PageLines(Key key,List<Run> runs,boolean limited){}
    private final Key key;
    private final byte[] borders;
    private final int[] interior;
    private MapShorelines(Key key,byte[] borders,int[] interior){
        this.key=key;this.borders=borders;this.interior=interior;
    }
    static byte classify(RegionPageStore.Aggregate a,long area,long[] histogram){
        if(a.known()!=area)return UNKNOWN;
        long water=histogram[FeatureDictionary.Terrain.WATER.ordinal()];
        if(water==area)return WATER;
        if(water==0&&a.wetCount()==0)return LAND;
        return UNKNOWN; // mixed cells have no exact shoreline position
    }
    private static boolean contact(byte a,byte b){return a!=UNKNOWN&&b!=UNKNOWN&&a!=b;}
    static MapShorelines build(Key key,byte[] contacts){
        if(contacts.length!=EDGE*EDGE)throw new IllegalArgumentException("Page coverage");
        // Only perimeter samples are retained; the interior is represented by packed runs.
        byte[] borders=new byte[4*EDGE];
        for(int i=0;i<EDGE;i++){
            borders[i]=contacts[i*EDGE];borders[EDGE+i]=contacts[i*EDGE+31];
            borders[2*EDGE+i]=contacts[i];borders[3*EDGE+i]=contacts[31*EDGE+i];
        }
        boolean land=false,water=false;
        for(byte c:contacts){land|=c==LAND;water|=c==WATER;}
        if(!land||!water)return new MapShorelines(key,borders,new int[0]);
        int[] runs=new int[2*EDGE*(EDGE-1)];int count=0;
        for(int x=1;x<EDGE;x++){
            int start=-1;
            for(int z=0;z<=EDGE;z++){
                boolean edge=z<EDGE&&contact(contacts[z*EDGE+x-1],contacts[z*EDGE+x]);
                if(edge&&start<0)start=z;
                else if(!edge&&start>=0){runs[count++]=pack(x,start,z-start,true);start=-1;}
            }
        }
        for(int z=1;z<EDGE;z++){
            int start=-1;
            for(int x=0;x<=EDGE;x++){
                boolean edge=x<EDGE&&contact(contacts[(z-1)*EDGE+x],contacts[z*EDGE+x]);
                if(edge&&start<0)start=x;
                else if(!edge&&start>=0){runs[count++]=pack(start,z,x-start,false);start=-1;}
            }
        }
        return new MapShorelines(key,borders,Arrays.copyOf(runs,count));
    }
    public Key key(){return key;}
    private static int pack(int x,int z,int length,boolean vertical){return x|(z<<6)|(length<<12)|(vertical?1<<18:0);}
    private static Run unpack(int code){int x=code&63,z=(code>>>6)&63,n=(code>>>12)&63;return (code&(1<<18))!=0?new Run(x,z,x,z+n):new Run(x,z,x+n,z);}
    public int interiorCount(){return interior.length;}
    public List<Run> interior(){return Arrays.stream(interior).mapToObj(MapShorelines::unpack).toList();}
    /** Only east/south owner emits a shared page boundary, avoiding duplicate lines. */
    public List<Run> border(MapShorelines neighbor,boolean east){
        if(neighbor==null)return List.of();
        var expected=new Key(key.lod(),key.x()+(east?1:0),key.z()+(east?0:1));
        if(!expected.equals(neighbor.key))throw new IllegalArgumentException("Mismatched shoreline neighbor");
        List<Run> out=new ArrayList<>();int start=-1;
        for(int i=0;i<=EDGE;i++){
            boolean edge=i<EDGE&&contact(borders[(east?1:3)*EDGE+i],neighbor.borders[(east?0:2)*EDGE+i]);
            if(edge&&start<0)start=i;
            else if(!edge&&start>=0){
                out.add(east?new Run(32,start,32,i):new Run(start,32,i,32));start=-1;
            }
        }
        return List.copyOf(out);
    }
    /** Bounded plan from already drawn exact pages, in caller's stable priority order. */
    public static List<PageLines> plan(List<MapShorelines> pages,int limit){
        if(limit<0||limit>MAX_SEGMENTS||pages.size()>384)throw new IllegalArgumentException("Shoreline budget");
        Map<Key,MapShorelines> byKey=new HashMap<>();
        for(var p:pages)if(byKey.put(p.key,p)!=null)throw new IllegalArgumentException("Duplicate page");
        List<PageLines> out=new ArrayList<>();int remaining=limit;
        for(var p:pages){
            var k=p.key;
            List<Run> runs=new ArrayList<>(Math.min(remaining,p.interior.length+32));
            int count=Math.min(remaining,p.interior.length);
            for(int i=0;i<count;i++)runs.add(unpack(p.interior[i]));
            remaining-=count;boolean limited=count<p.interior.length;
            for(var source:List.of(p.border(byKey.get(new Key(k.lod(),k.x()+1,k.z())),true),
                    p.border(byKey.get(new Key(k.lod(),k.x(),k.z()+1)),false))){
                int n=Math.min(remaining,source.size());runs.addAll(source.subList(0,n));remaining-=n;
                limited|=n<source.size();
            }
            if(!runs.isEmpty()||limited)out.add(new PageLines(k,List.copyOf(runs),limited));
        }
        return List.copyOf(out);
    }
}
