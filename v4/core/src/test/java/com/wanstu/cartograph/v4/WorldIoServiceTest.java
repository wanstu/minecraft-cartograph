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
                var feed=io.presentationChanges("minecraft:overworld");
                long before=feed.sequence();
                var raster=io.raster("minecraft:overworld",key).get(15,TimeUnit.SECONDS);
                check(raster.coverage(16)==255,"async raster from real geography");
                io.write("minecraft:overworld",-1,0,columns).get(15,TimeUnit.SECONDS);
                check(feed.sequence()==before,"no-op snapshot does not invalidate");
                Entry[] changed=columns.clone();changed[0]=Entry.leaf(leaf.feature(),75,75,0,1,2,3);
                io.write("minecraft:overworld",-1,0,changed).get(15,TimeUnit.SECONDS);
                check(feed.since(before).keys().contains(key)&&feed.since(before).keys().size()==11,"durable local invalidation");
                var updated=io.raster("minecraft:overworld",key).get(15,TimeUnit.SECONDS);
                check(updated.generation()!=raster.generation()&&updated.surface(16)==75,"updated async raster");
                var marker=MarkerStoreTest.marker(1);
                var markerChange=MarkerStoreTest.change(MarkerStore.Action.UPSERT,marker);
                var markers=io.changeMarker("minecraft:overworld",0,markerChange).get(15,TimeUnit.SECONDS);
                check(markers.active().equals(List.of(marker))&&markers.revision()==1,"async marker commit");
                check(io.markers("minecraft:the_nether").get(15,TimeUnit.SECONDS).active().isEmpty(),"marker dimension isolation");
                try{
                    io.changeMarker("minecraft:overworld",0,markerChange).get(15,TimeUnit.SECONDS);
                    throw new AssertionError("Stale marker accepted");
                }catch(ExecutionException expected){check(expected.getCause() instanceof IllegalStateException,"stale marker failure");}
                check(io.markers("minecraft:overworld").get(15,TimeUnit.SECONDS).equals(markers),"stale mutation preserves data");
                var queue=new DirtyHandoffQueue(4);
                var receipt=queue.submit(new InactiveDirty(new Position(-1,0),1));
                check(io.handoff("minecraft:overworld",queue).get(15,TimeUnit.SECONDS)==1&&receipt.isDone(),"dirty handoff");
            }finally{check(io.close(15,TimeUnit.SECONDS),"drained close");}
            io=new WorldIoService(root,1,16);
            try{
                check(io.pendingDirty("minecraft:overworld").get(15,TimeUnit.SECONDS)==1,"durable dirty restart");
                check(io.pendingDirty("minecraft:the_nether").get(15,TimeUnit.SECONDS)==0,"dirty dimension separation");
                check(io.markers("minecraft:overworld").get(15,TimeUnit.SECONDS).active().equals(List.of(MarkerStoreTest.marker(1))),"markers restart");
                Path markerFile=root.resolve(HexFormat.of().formatHex("minecraft:overworld".getBytes(java.nio.charset.StandardCharsets.UTF_8))).resolve("markers.cv4");
                byte[] broken=Files.readAllBytes(markerFile);broken[broken.length-1]^=1;Files.write(markerFile,broken);
                try{
                    io.markers("minecraft:overworld").get(15,TimeUnit.SECONDS);
                    throw new AssertionError("Corrupt marker read accepted");
                }catch(ExecutionException expected){check(expected.getCause() instanceof java.io.UncheckedIOException,"marker corruption failure");}
                check(!io.failed()&&io.raster("minecraft:overworld",Key.of(new GeographicPyramid.Cell(0,-16,0))).get(15,TimeUnit.SECONDS).surface(16)==75,"marker failure does not poison geography");
            }finally{check(io.close(15,TimeUnit.SECONDS),"close");}
            System.out.println("PASS: shared world IO, dimension isolation/eviction, live snapshot durable, read, dirty handoff and restart, marker durable receipts and corruption isolation");
        }finally{ScaleTest.delete(root);}
    }
}
