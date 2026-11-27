package com.wanstu.cartograph.v4;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.MarkerStore.*;
public final class MarkerStoreTest{
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    static Marker marker(int i){return new Marker(new UUID(0,i),-i,70,i,"标点 "+i,"简介",Icon.PORT,0xabc123);}
    static Change change(Action a,Marker m){return new Change(a,m.id(),a==Action.UPSERT?m:null);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("markers-v4-");Path p=root.resolve("overworld/markers.cv4");var s=new MarkerStore(p);
        try{
            check(s.read().revision()==0&&!Files.exists(p),"read does not create");
            var m=marker(1);var a=s.apply(0,change(Action.UPSERT,m));
            check(a.revision()==1&&new MarkerStore(p).read().equals(a),"durable unicode");
            check(s.apply(1,change(Action.UPSERT,m)).revision()==1,"no-op");
            try{s.apply(0,change(Action.DELETE,m));throw new AssertionError("stale");}catch(IllegalStateException expected){}
            var edit=new Marker(m.id(),1,80,2,"改名","新描述",Icon.HOME,0xffffff);
            a=s.apply(1,change(Action.UPSERT,edit));a=s.apply(a.revision(),change(Action.DELETE,edit));
            check(a.active().isEmpty()&&a.trash().equals(List.of(edit)),"delete");
            a=s.apply(a.revision(),change(Action.RESTORE,edit));check(a.active().equals(List.of(edit)),"restore");
            for(int i=2;i<=103;i++){
                var n=marker(i);a=s.apply(a.revision(),change(Action.UPSERT,n));a=s.apply(a.revision(),change(Action.DELETE,n));
            }
            check(a.trash().size()==100&&a.trash().getFirst().id().equals(marker(103).id())&&a.trash().getLast().id().equals(marker(4).id()),"trash cap");
            a=s.apply(a.revision(),change(Action.PURGE,marker(103)));check(a.trash().size()==99,"purge");
            check(new MarkerStore(root.resolve("nether/markers.cv4")).read().active().isEmpty(),"dimension isolation");
            byte[] b=Files.readAllBytes(p);b[b.length-1]^=1;Files.write(p,b);
            try{s.apply(a.revision(),change(Action.DELETE,edit));throw new AssertionError("corruption");}catch(java.io.IOException expected){}
            check(Arrays.equals(b,Files.readAllBytes(p)),"damaged data untouched");
            System.out.println("PASS: atomic marker CRUD, Unicode, stale revision, no-op, 100 trash cap, restore/purge, dimension isolation, corruption fail closed");
        }finally{ScaleTest.delete(root);}
    }
}
