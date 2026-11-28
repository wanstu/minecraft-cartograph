package com.wanstu.cartograph.v4;

import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.ActiveChunkScheduler.Position;
import static com.wanstu.cartograph.v4.RegionPageStore.Entry;

/** Offline replay of real Anvil data and a copied durable dirty log.
 * Lifecycle events are simulated; does not launch Minecraft or reproduce native map colors.
 */
public final class ArchiveLifecycleProbe {
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception{
        Path source=Path.of(args[0]),regions=source.resolve("region");
        String dim=HexFormat.of().formatHex("minecraft:overworld".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path log=source.resolve("cartograph-v4-dev").resolve(dim).resolve("dirty.cv4log");
        byte[] original=Files.readAllBytes(log);
        Path root=Files.createTempDirectory("cartograph-real-archive-replay-");
        try{
            Files.copy(log,root.resolve("dirty.cv4log"));
            var dirty=new SavedDirtyIndex(root);
            var work=dirty.peek(256);
            check(work.size()==49&&dirty.size()==49,"expected 49 captured fixture handoffs");
            var gate=new LiveArchiveGate();
            // Protect all existing sources during discovery. Only the 49 real pending
            // positions will be released; no unnecessary writes for unrelated regions.
            int protectedCount=0;
            try(var files=Files.list(regions)){
                for(Path path:files.filter(p->p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).toList()){
                    String[] fields=path.getFileName().toString().split("\\.");
                    try(var reader=new AnvilRegionReader(path,Integer.parseInt(fields[1]),Integer.parseInt(fields[2]))){
                        for(var c:reader.discover()){gate.loaded(c.x(),c.z());protectedCount++;}
                    }
                }
            }
            var world=new WorldGeography(root,4,16,4*1024*1024,4*1024*1024);
            Map<Position,Entry[]> expected=new LinkedHashMap<>();
            Map<RegionShard.Id,List<DiskGeographicPyramid.ChunkSnapshot>> batches=new LinkedHashMap<>();
            for(var w:work){
                Position p=w.position();int rx=Math.floorDiv(p.x(),32),rz=Math.floorDiv(p.z(),32);
                try(var reader=new AnvilRegionReader(regions.resolve("r."+rx+"."+rz+".mca"),rx,rz)){
                    var saved=SavedChunkSurface.decode(reader.load(reader.identity(p.x(),p.z())).nbt(),p.x(),p.z(),-64,384);
                    // Same deterministic test-only mapping for the seeded live and saved facts.
                    Entry[] columns=ArchiveReconcilerTest.map(saved);
                    check(Arrays.stream(columns).allMatch(Objects::nonNull),"real source complete: "+p);
                    expected.put(p,columns);
                    batches.computeIfAbsent(RegionShard.Id.ofChunk(p.x(),p.z()),k->new ArrayList<>()).add(new DiskGeographicPyramid.ChunkSnapshot(p.x(),p.z(),columns));
                }
            }
            for(var batch:batches.entrySet()){world.ensureShard(batch.getKey());world.updateChunks(batch.getKey(),batch.getValue());}
            for(Position p:expected.keySet())gate.committed(p.x(),p.z());
            try(var archive=new ArchiveReconciler(regions,root.resolve("source"),-64,384,s->FreshRegionBuilder.Columns.fromEntries(ArchiveReconcilerTest.map(s)))){
                ArchiveReconciler.Progress p=null;
                for(int i=0;i<256;i++){p=archive.step(world,dirty,gate,16);if(p.discovered()&&p.waitingActive()==49)break;}
                check(p!=null&&p.discovered()&&p.waitingActive()==49&&p.pending()==49,"real pending set parked");
                long deferrals=p.deferred();
                for(int i=0;i<200;i++){p=archive.step(world,dirty,gate,16);check(p.deferred()==deferrals&&p.waitingActive()==49,"parked quiet");}
                int count=0;
                for(Position pos:expected.keySet()){if(count++<24)gate.unloaded(pos.x(),pos.z());}
                for(int i=0;i<20&&dirty.size()!=25;i++)p=archive.step(world,dirty,gate,16);
                check(p.pending()==25&&p.waitingActive()==25&&p.unchanged()==24,"partial unload");
                for(Position pos:expected.keySet())gate.unloaded(pos.x(),pos.z());
                for(int i=0;i<20&&dirty.size()!=0;i++)p=archive.step(world,dirty,gate,16);
                check(p.pending()==0&&p.waitingActive()==0&&p.unchanged()==49&&p.sourceErrors()==0&&p.committed()==0,"all released exact facts acknowledged");
                for(var item:expected.entrySet())check(world.matchesChunk(item.getKey().x(),item.getKey().z(),item.getValue()),"replayed exact facts");
                check(new SavedDirtyIndex(root).size()==0,"durable acknowledgement");
                check(Arrays.equals(original,Files.readAllBytes(log)),"source log unchanged");
                System.out.printf(Locale.ROOT,"PASS: real Anvil replay protected=%d parked=49 idleSteps=200 additionalDeferrals=0 partialRemaining=25 finalPending=%d decoded=%d unchanged=%d committed=%d sourceErrors=%d columns=%d%n",
                    protectedCount,p.pending(),p.decoded(),p.unchanged(),p.committed(),p.sourceErrors(),expected.size()*256);
            }
        }finally{ScaleTest.delete(root);}
    }
}
