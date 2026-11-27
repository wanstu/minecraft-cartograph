package com.wanstu.cartograph.v4;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.MarkerStore.Action;
public final class NamedRegionTest{
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    static NamedRegion region(int i,int x0,int z0,int x1,int z1){return NamedRegion.between(new UUID(0,i),x0,z0,x1,z1,"聚落 "+i,"区域简介",0x55aacc);}
    static NamedRegionStore.Change change(Action a,NamedRegion r){return new NamedRegionStore.Change(a,r.id(),a==Action.UPSERT?r:null);}
    public static void main(String[] args)throws Exception{
        var a=region(1,10,20,-10,-20);var b=region(2,-2,-2,2,2);
        check(a.area()==861&&a.centerX()==.5&&a.centerZ()==.5,"inclusive negative bounds");
        check(a.contains(10.99,20.99)&&!a.contains(11,21),"half-open block extent");
        check(NamedRegion.select(List.of(a,b),0,0)==b,"smallest overlap");
        check(NamedRegion.select(List.of(a,b),30,30)==null,"outside");
        check(!a.intersects(new MapProjection.Bounds(11,21,12,22)),"outside bounds");
        check(region(3,-30000000,-30000000,30000000,30000000).area()==3600000120000001L,"world extent no overflow");
        Path root=Files.createTempDirectory("cv4-named-regions");Path p=root.resolve("overworld/regions.cv4");
        try{
            var s=new NamedRegionStore(p);check(s.read().revision()==0&&!Files.exists(p),"empty read");
            var snap=s.apply(0,change(Action.UPSERT,a));check(new NamedRegionStore(p).read().equals(snap),"reopen unicode");
            check(s.apply(1,change(Action.UPSERT,a)).revision()==1,"unchanged");
            try{s.apply(0,change(Action.DELETE,a));throw new AssertionError("stale");}catch(IllegalStateException expected){}
            snap=s.apply(1,change(Action.DELETE,a));check(snap.trash().equals(List.of(a)),"delete");
            snap=s.apply(snap.revision(),change(Action.RESTORE,a));check(snap.active().equals(List.of(a)),"restore");
            for(int i=2;i<104;i++){var r=region(i,0,0,1,1);snap=s.apply(snap.revision(),change(Action.UPSERT,r));snap=s.apply(snap.revision(),change(Action.DELETE,r));}
            check(snap.trash().size()==100&&snap.trash().getFirst().id().equals(new UUID(0,103)),"trash bound");
            snap=s.apply(snap.revision(),change(Action.PURGE,snap.trash().getFirst()));check(snap.trash().size()==99,"purge");
            check(new NamedRegionStore(root.resolve("nether/regions.cv4")).read().active().isEmpty(),"dimension isolation");
            byte[] bytes=Files.readAllBytes(p);bytes[bytes.length-1]^=1;Files.write(p,bytes);
            try{s.apply(snap.revision(),change(Action.DELETE,a));throw new AssertionError("corrupt");}catch(java.io.IOException expected){}
            check(Arrays.equals(bytes,Files.readAllBytes(p)),"corrupt unchanged");
            System.out.println("PASS: canonical region bounds, overlap selection, world extent, revision, Unicode restart, trash cap and CRC");
        }finally{ScaleTest.delete(root);}
    }
}
