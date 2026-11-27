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
                gate.loaded(-2,3);archive.step(world,dirty,gate,16);
                check(dirty.size()==1&&world.matchesChunk(-2,3,live),"active exclusion");
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
