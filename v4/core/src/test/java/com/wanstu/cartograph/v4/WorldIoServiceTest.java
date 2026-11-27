package com.wanstu.cartograph.v4;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
import static com.wanstu.cartograph.v4.ActiveChunkScheduler.*;
public final class WorldIoServiceTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-world-io-");
        try{
            var leaf=Entry.leaf(new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK),70,70,0,1,2,3);
            Entry[] columns=new Entry[256];Arrays.fill(columns,leaf);
            var io=new WorldIoService(root,1,16);
            try{
                check(io.write("minecraft:overworld",-1,0,columns).get(15,TimeUnit.SECONDS)==GeographicWorker.WriteResult.COMMITTED,"live commit");
                var key=Key.of(new GeographicPyramid.Cell(0,-16,0));
                var original=io.read("minecraft:overworld",key).get(15,TimeUnit.SECONDS);check(original.data().size()==256,"world read");
                check(io.read("minecraft:the_nether",key).get(15,TimeUnit.SECONDS).data().size()==0,"dimension separation");
                var reopened=io.read("minecraft:overworld",key).get(15,TimeUnit.SECONDS);check(reopened.data().size()==256&&reopened.generation()!=original.generation(),"dimension eviction and reopen has distinct upload token");
                var queue=new DirtyHandoffQueue(4);
                var receipt=queue.submit(new InactiveDirty(new Position(-1,0),1));
                check(io.handoff("minecraft:overworld",queue).get(15,TimeUnit.SECONDS)==1&&receipt.isDone(),"dirty handoff");
            }finally{check(io.close(15,TimeUnit.SECONDS),"drained close");}
            io=new WorldIoService(root,1,16);
            try{
                check(io.pendingDirty("minecraft:overworld").get(15,TimeUnit.SECONDS)==1,"durable dirty restart");
                check(io.pendingDirty("minecraft:the_nether").get(15,TimeUnit.SECONDS)==0,"dirty dimension separation");
            }finally{check(io.close(15,TimeUnit.SECONDS),"close");}
            System.out.println("PASS: shared world IO, dimension isolation/eviction, live snapshot durable, read, dirty handoff and restart");
        }finally{ScaleTest.delete(root);}
    }
}
