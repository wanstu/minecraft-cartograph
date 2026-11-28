package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
import static com.wanstu.cartograph.v4.ActiveChunkScheduler.Position;

public final class ArchiveReconcilerTest{
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    static Entry[] map(SavedChunkSurface.Snapshot s){
        var result=new Entry[256];var columns=s.columns();
        for(int i=0;i<256;i++){
            var c=columns[i];if(c==null)continue;
            result[i]=Entry.leaf(new FeatureDictionary.Feature(c.material(),c.biome(),FeatureDictionary.Terrain.OTHER),
                c.surfaceY(),c.reliefY(),0,1,2,3);
        }
        return result;
    }
    static void write(Path file,byte[] nbt)throws IOException{
        int slot=3*32+30,sectors=(nbt.length+5+4095)/4096;
        try(var out=new RandomAccessFile(file.toFile(),"rw")){
            out.setLength((2L+sectors)*4096);
            out.seek(slot*4L);out.writeInt((2<<8)|sectors);
            out.seek(4096+slot*4L);out.writeInt(123);
            out.seek(8192);out.writeInt(nbt.length+1);out.writeByte(3);out.write(nbt);
        }
    }
    static ArchiveReconciler open(Path regions,Path target)throws IOException{
        return new ArchiveReconciler(regions,target.resolve("source"),-16,16,s->FreshRegionBuilder.Columns.fromEntries(map(s)));
    }
    static ArchiveReconciler.Progress drain(ArchiveReconciler a,WorldGeography world,SavedDirtyIndex dirty,LiveArchiveGate gate)throws IOException{
        ArchiveReconciler.Progress p=null;
        for(int i=0;i<20;i++){p=a.step(world,dirty,gate,16);if(p.discovered())return p;}
        throw new AssertionError("Discovery not exhausted");
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-archive-reconcile-");
        try{
            Path regions=Files.createDirectory(root.resolve("region")),target=root.resolve("geo");
            Path file=regions.resolve("r.-1.0.mca");
            byte[] nbt=SavedChunkSurfaceTest.fixture();write(file,nbt);
            var world=new WorldGeography(target,2,8,4*1024*1024,4*1024*1024);
            var dirty=new SavedDirtyIndex(target);var gate=new LiveArchiveGate();
            var expected=map(SavedChunkSurface.decode(nbt,-2,3,-16,16));
            try(var archive=open(regions,target)){
                var p=drain(archive,world,dirty,gate);
                check(p.decoded()==1&&p.committed()==1&&world.matchesChunk(-2,3,expected),"first discovery");
            }
            try(var archive=open(regions,target)){
                var p=drain(archive,world,dirty,gate);
                check(p.decoded()==0&&p.unchanged()==1&&p.committed()==0,"restart avoids NBT decode");
                // An identical saved revision may acknowledge a durable dirty handoff.
                dirty.mark(List.of(new Position(-2,3)));
                archive.step(world,dirty,gate,16);check(dirty.size()==0,"matching source ack");
                // A live capture changes geography. Old fingerprint must not hide the change.
                var live=expected.clone();
                live[0]=Entry.leaf(new FeatureDictionary.Feature("minecraft:diamond_block","minecraft:plains",FeatureDictionary.Terrain.OTHER),-13,-14,0,1,2,3);
                world.updateChunks(RegionShard.Id.ofChunk(-2,3),List.of(new DiskGeographicPyramid.ChunkSnapshot(-2,3,live)));
                gate.committed(-2,3);dirty.mark(List.of(new Position(-2,3)));
                archive.step(world,dirty,gate,16);
                check(world.matchesChunk(-2,3,live)&&dirty.size()==1,"stale save cannot overwrite live");
                gate.loaded(-2,3);var parked=archive.step(world,dirty,gate,16);
                check(dirty.size()==1&&parked.waitingActive()==1&&dirty.peek(16).isEmpty()&&world.matchesChunk(-2,3,live),"active exclusion and park");
                long deferred=parked.deferred();
                for(int i=0;i<100;i++)check(archive.step(world,dirty,gate,16).deferred()==deferred,"no repeated active retries");
                check(new SavedDirtyIndex(target).peek(16).size()==1,"park remains durable across restart");
                dirty.mark(List.of(new Position(-2,3)));
                check(archive.step(world,dirty,gate,16).waitingActive()==1,"new token parked");
                gate.unloaded(-2,3);
                var awakened=archive.step(world,dirty,gate,16);
                check(awakened.waitingActive()==0&&dirty.size()==1&&world.matchesChunk(-2,3,live),"unload wakes but cannot overwrite newer live");
                gate.loaded(-2,3);archive.step(world,dirty,gate,16);
                gate.unloaded(-2,3);gate.loaded(-2,3);
                check(archive.step(world,dirty,gate,16).waitingActive()==1,"reload before wake reparks");
                gate.unloaded(-2,3);
                // A reload/unload during a read invalidates that reservation.
                check(gate.begin(-2,3),"reserve");gate.loaded(-2,3);gate.unloaded(-2,3);
                check(!gate.valid(-2,3),"load/unload invalidates read");gate.end(-2,3);
            }
            // On a new server session the frozen disk is authoritative again.
            world=new WorldGeography(target,2,8,4*1024*1024,4*1024*1024);
            dirty=new SavedDirtyIndex(target);gate=new LiveArchiveGate();
            try(var archive=open(regions,target)){
                drain(archive,world,dirty,gate);
                check(world.matchesChunk(-2,3,expected)&&dirty.size()==0,"restart reconciliation");
            }
            // Content changes without header timestamp change must not be skipped.
            byte[] modified=nbt.clone();byte[] old="test:ordered".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] replacement="test:changed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int offset=-1;
            for(int i=0;i<=modified.length-old.length;i++)if(Arrays.equals(modified,i,i+old.length,old,0,old.length)){offset=i;break;}
            check(offset>=0,"fixture key");System.arraycopy(replacement,0,modified,offset,replacement.length);write(file,modified);
            try(var archive=open(regions,target)){
                var p=drain(archive,world,dirty,gate);
                check(p.decoded()==1&&p.committed()==1&&world.matchesChunk(-2,3,map(SavedChunkSurface.decode(modified,-2,3,-16,16))),"same header changed bytes");
            }
            // A separate index sees persisted starts even when terrain does not change.
            var structures=new StructureIndex(target.resolve("structures"));
            byte[] withStart=SavedChunkSurfaceTest.withStarts(modified,-2,3);
            write(file,withStart);
            try(var archive=new ArchiveReconciler(regions,target.resolve("structure-fixture-source"),-16,16,
                    source->FreshRegionBuilder.Columns.fromEntries(map(source)),structures,()->{})){
                var p=drain(archive,world,dirty,gate);
                check(p.committed()==0&&structures.matches(-2,3,List.of(new StructureIndex.Start("minecraft:village_plains",-2,3,-25,64,true))),"saved structure only update");
            }
            structures.replace(-2,3,List.of(new StructureIndex.Start("test:live",-2,3)));gate.committed(-2,3);
            // The saved bytes AND terrain page revision are unchanged, but live starts
            // have changed. A terrain-only fingerprint hit must not acknowledge the handoff.
            dirty.mark(List.of(new Position(-2,3)));
            try(var archive=new ArchiveReconciler(regions,target.resolve("structure-fixture-source"),-16,16,
                    source->FreshRegionBuilder.Columns.fromEntries(map(source)),structures,()->{})){
                drain(archive,world,dirty,gate);
                check(dirty.size()==1&&structures.matches(-2,3,List.of(new StructureIndex.Start("test:live",-2,3))),
                    "unchanged saved fingerprint cannot clear structure-only live conflict");
            }
            write(file,modified);
            try(var archive=new ArchiveReconciler(regions,target.resolve("structure-fixture-source"),-16,16,
                    source->FreshRegionBuilder.Columns.fromEntries(map(source)),structures,()->{})){
                drain(archive,world,dirty,gate);
                check(structures.matches(-2,3,List.of(new StructureIndex.Start("test:live",-2,3))),"stale saved starts do not replace live");
            }
            gate=new LiveArchiveGate();
            try(var archive=new ArchiveReconciler(regions,target.resolve("structure-fixture-source"),-16,16,
                    source->FreshRegionBuilder.Columns.fromEntries(map(source)),structures,()->{})){
                drain(archive,world,dirty,gate);
                check(structures.matches(-2,3,List.of()),"saved authoritative removal next session");
            }
            // Source index is derived: corrupted metadata cannot cause a false skip or damage geography.
            Path fingerprint=target.resolve("source/r.-1.0.cv4src");
            byte[] corrupted=Files.readAllBytes(fingerprint);corrupted[20]^=1;Files.write(fingerprint,corrupted);
            try(var archive=open(regions,target)){
                var p=drain(archive,world,dirty,gate);
                check(p.decoded()==1&&p.unchanged()==1&&p.committed()==0,"damaged fingerprint reverified");
            }
            // A different chunk in the same leaf page may change the page revision.
            // Conservatively reverify the original chunk, without rewriting its data.
            var neighbor=map(SavedChunkSurface.decode(modified,-2,3,-16,16));
            world.updateChunks(RegionShard.Id.ofChunk(-1,3),List.of(new DiskGeographicPyramid.ChunkSnapshot(-1,3,neighbor)));
            try(var archive=open(regions,target)){
                var p=drain(archive,world,dirty,gate);
                check(p.decoded()==1&&p.unchanged()==1&&p.committed()==0,"neighbor revision reverified");
            }
            // A failed region header must never count as completed discovery.
            Path retryRegions=Files.createDirectory(root.resolve("retry-regions")),retryTarget=root.resolve("retry-geo");
            Path broken=retryRegions.resolve("r.-1.0.mca");
            Files.write(broken,new byte[8]);
            Files.write(retryRegions.resolve("r.0.0.mca"),new byte[8192]);
            var retryWorld=new WorldGeography(retryTarget,2,8,4*1024*1024,4*1024*1024);
            var retryDirty=new SavedDirtyIndex(retryTarget);var retryGate=new LiveArchiveGate();
            try(var archive=open(retryRegions,retryTarget)){
                for(int i=0;i<5;i++){
                    var p=archive.step(retryWorld,retryDirty,retryGate,16);
                    check(!p.discovered()&&p.sourceErrors()>0&&p.lastSourceError().contains("r.-1.0.mca"),"failed header remains pending with diagnostics");
                }
                // Healthy region must not be enumerated again on the failed-header retry pass.
                Files.write(retryRegions.resolve("r.0.0.mca"),new byte[8]);
                write(broken,nbt);
                var p=drain(archive,retryWorld,retryDirty,retryGate);
                check(p.committed()==1&&retryWorld.matchesChunk(-2,3,expected),"repaired header discovered exactly");
            }
            // An inaccessible/non-directory region path must not abort the IO owner or
            // falsely mark discovery complete; it can become readable in a later step.
            Path blocked=root.resolve("blocked-regions");
            Files.write(blocked,new byte[1]);
            Path blockedTarget=root.resolve("blocked-geo");
            var blockedWorld=new WorldGeography(blockedTarget,2,8,4*1024*1024,4*1024*1024);
            var blockedDirty=new SavedDirtyIndex(blockedTarget);
            try(var archive=open(blocked,blockedTarget)){
                var p=archive.step(blockedWorld,blockedDirty,new LiveArchiveGate(),16);
                check(!p.discovered()&&p.sourceErrors()==1,"directory open failure stays retryable");
                Files.delete(blocked);Files.createDirectory(blocked);write(blocked.resolve("r.-1.0.mca"),nbt);
                p=drain(archive,blockedWorld,blockedDirty,new LiveArchiveGate());
                check(p.committed()==1&&blockedWorld.matchesChunk(-2,3,expected),"directory recovery");
            }
            // A permanently unavailable dirty chunk must not monopolize a
            // one-candidate archive budget while the server is under pressure.
            Path fairTarget=root.resolve("fair-geo");
            var fairWorld=new WorldGeography(fairTarget,2,8,4*1024*1024,4*1024*1024);
            var fairDirty=new SavedDirtyIndex(fairTarget);
            fairDirty.mark(List.of(new Position(3200,3200)));
            var fairGate=new LiveArchiveGate();
            try(var archive=open(blocked,fairTarget)){
                ArchiveReconciler.Progress p=null;
                for(int i=0;i<12;i++){
                    p=archive.step(fairWorld,fairDirty,fairGate,1);
                    if(p.discovered())break;
                }
                check(p!=null&&p.discovered()&&p.committed()==1&&fairDirty.size()==1
                    &&fairWorld.matchesChunk(-2,3,expected),"one-candidate discovery fairness");
            }
            // A same-session clean unload avoids immediate duplicate capture.
            var scheduler=new ActiveChunkScheduler(t->null,12000,20,8);
            scheduler.loadedObserved(8,8);var ticket=scheduler.poll();scheduler.finish(ticket,CapturePump.Outcome.COMMITTED);
            scheduler.unloaded(8,8);scheduler.loadedObserved(8,8);
            check(scheduler.metrics().queuedDirty()==0&&scheduler.metrics().queuedReview()==1,"clean reload");
            scheduler.unloaded(8,8);scheduler.changed(8,8);scheduler.loadedObserved(8,8);
            check(scheduler.metrics().queuedDirty()==1,"save change invalidates clean reload");
            System.out.println("PASS: automatic archive discovery, durable fingerprint skip, same-header changed payload, live authority, active exclusion, stale reservation, dirty acknowledgement, corrupt fingerprint fallback, neighbor revision and clean reload");
        }finally{ScaleTest.delete(root);}
    }
}
