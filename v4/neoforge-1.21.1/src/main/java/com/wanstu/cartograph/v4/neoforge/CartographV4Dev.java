package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.dimension.DimensionType;
import java.nio.file.Path;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.BlockPos;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

/** Development-only live capture adapter. Not deployed with V3.1. */
@Mod("cartograph_v4_dev")
public final class CartographV4Dev {
    private static final Logger LOG=LoggerFactory.getLogger("cartograph.v4.dev");
    private static volatile Runtime runtime;
    public CartographV4Dev(){
        NeoForge.EVENT_BUS.addListener(CartographV4Dev::starting);
        NeoForge.EVENT_BUS.addListener(CartographV4Dev::loaded);
        NeoForge.EVENT_BUS.addListener(CartographV4Dev::unloaded);
        NeoForge.EVENT_BUS.addListener(CartographV4Dev::saved);
        NeoForge.EVENT_BUS.addListener(CartographV4Dev::tick);
        NeoForge.EVENT_BUS.addListener(CartographV4Dev::stopped);
    }
    /** Local client access only; returned service queues all geography work off-thread. */
    static WorldIoService localPresentation(MinecraftServer server){
        Runtime current=runtime;return current!=null&&current.server==server?current.io:null;
    }
    private static void starting(ServerAboutToStartEvent e){runtime=new Runtime(e.getServer());}
    private static void onServer(ServerLevel level,java.util.function.Consumer<Runtime> task){
        MinecraftServer server=level.getServer();
        Runnable run=()->{
            Runtime current=runtime;
            if(current!=null&&current.server==server)task.accept(current);
        };
        if(server.isSameThread())run.run();else server.execute(run);
    }
    private static void loaded(ChunkEvent.Load e){
        if(e.getLevel() instanceof ServerLevel level&&e.getChunk() instanceof LevelChunk){
            ChunkPos p=e.getChunk().getPos();onServer(level,r->{var d=r.dimension(level);d.gate.loaded(p.x,p.z);d.scheduler.loadedObserved(p.x,p.z);});
        }
    }
    private static void unloaded(ChunkEvent.Unload e){
        if(e.getLevel() instanceof ServerLevel level){
            ChunkPos p=e.getChunk().getPos();onServer(level,r->{var d=r.dimension(level);d.scheduler.unloaded(p.x,p.z);d.gate.unloaded(p.x,p.z);});
        }
    }
    private static void saved(ChunkDataEvent.Save e){
        if(e.getLevel() instanceof ServerLevel level&&e.getChunk() instanceof LevelChunk){
            ChunkPos p=e.getChunk().getPos();onServer(level,r->r.dimension(level).scheduler.changed(p.x,p.z));
        }
    }
    public static void changed(LevelChunk chunk,BlockPos pos){
        if(runtime==null||!(chunk.getLevel() instanceof ServerLevel level))return;
        if(level.getServer().isSameThread()){
            if(level.getChunkSource().getChunkNow(chunk.getPos().x,chunk.getPos().z)!=chunk)return;
            int x=pos.getX()&15,z=pos.getZ()&15;
            int floor=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z);
            int relief=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            int top=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
            if(pos.getY()<Math.min(top,Math.min(floor,relief))-1)return;
        }
        ChunkPos p=chunk.getPos();onServer(level,r->r.dimension(level).scheduler.changed(p.x,p.z));
    }
    private static void tick(ServerTickEvent.Post e){
        Runtime current=runtime;if(current!=null&&current.server==e.getServer())current.tick();
    }
    private static void stopped(ServerStoppedEvent e){
        if(runtime==null||runtime.server!=e.getServer())return;
        try{runtime.stop();}finally{runtime=null;}
    }
    private static final class Dimension {
        final ActiveChunkScheduler scheduler;
        final CapturePump pump;
        final DirtyHandoffQueue dirty=new DirtyHandoffQueue(128);
        CompletableFuture<Integer> handoff;
        final String key;
        final LiveArchiveGate gate;final Path regions;final int minY,height;
        CompletableFuture<WorldIoService.ArchiveProgress> archive;
        WorldIoService.ArchiveProgress progress;
        long nextArchive;
        Dimension(ServerLevel level,WorldIoService io){
            key=level.dimension().location().toString();
            gate=io.gate(key);
            regions=DimensionType.getStorageFolder(level.dimension(),level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
            minY=level.getMinBuildHeight();height=level.getHeight();
            scheduler=new ActiveChunkScheduler(t->MinecraftSurfaceFacts.begin(level,t),12000,20,8);
            pump=new CapturePump(scheduler,(x,z,columns)->{
                LevelChunk loaded=level.getChunkSource().getChunkNow(x,z);
                List<StructureIndex.Start> starts=null;
                if(loaded!=null){
                    var registry=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                    starts=new ArrayList<>();
                    for(var entry:loaded.getAllStarts().entrySet()){
                        var start=entry.getValue();var id=registry.getKey(entry.getKey());
                        if(start==null||!start.isValid()||id==null)continue;
                        if(start.getChunkPos().x!=x||start.getChunkPos().z!=z)continue;
                        var footprint=new StructureIndex.Footprint();
                        var pieces=start.getPieces();
                        if(pieces.size()<=4096)for(var piece:pieces){
                            var box=piece.getBoundingBox();
                            footprint.add(box.minX(),box.minZ(),box.maxX(),box.maxZ());
                        }else footprint.invalidate();
                        starts.add(footprint.finish(id.toString(),x,z));
                    }
                }
                return io.write(key,x,z,columns,starts);
            },2);
        }
        void archive(WorldIoService io,long tick){
            if(archive!=null){
                if(!archive.isDone())return;
                try{
                    var next=archive.join();
                    boolean madeProgress=progress==null||next.committed()>progress.committed()||next.unchanged()>progress.unchanged();
                    if(next.discovered())nextArchive=tick+(next.pending()>0&&madeProgress?2:100);
                    else if(progress!=null&&next.sourceErrors()>progress.sourceErrors()&&!madeProgress)nextArchive=tick+20;
                    progress=next;
                }catch(CompletionException e){nextArchive=tick+200;}
                archive=null;
            }
            if(tick<nextArchive||io.failed()||io.queued()>8)return;
            nextArchive=tick+2;
            archive=io.archiveStep(key,regions,minY,height,MinecraftSurfaceFacts::fromSaved,16);
        }
        void transfer(WorldIoService io){
            scheduler.drainInactiveAsync(16,dirty);
            if(dirty.size()>0&&(handoff==null||handoff.isDone())){
                if(handoff!=null&&handoff.isCompletedExceptionally()&&io.failed())return;
                handoff=io.handoff(key,dirty);
            }
        }
    }
    private static final class Runtime{
        final MinecraftServer server;
        final WorldIoService io;
        final Map<ServerLevel,Dimension> dimensions=new LinkedHashMap<>();
        int cursor,archiveCursor,maintenanceCursor;
        CompletableFuture<RegionPageStore.Compaction> maintenance;
        long tick;
        Runtime(MinecraftServer server){
            this.server=server;
            io=new WorldIoService(server.getWorldPath(LevelResource.ROOT).resolve("cartograph-v4-dev"),3,128);
        }
        Dimension dimension(ServerLevel level){return dimensions.computeIfAbsent(level,l->new Dimension(l,io));}
        void tick(){
            tick++;
            if(tick%200==0)for(Dimension d:dimensions.values())LOG.info("V4 capture {}: {} / {}",d.key,d.scheduler.metrics(),d.pump.metrics());
            if(tick%200==0)for(Dimension d:dimensions.values())if(d.progress!=null)LOG.info("V4 archive {}: {}",d.key,d.progress);
            if(tick==1||tick%200==0)for(ServerLevel level:server.getAllLevels())dimension(level);
            List<Dimension> all=new ArrayList<>(dimensions.values());
            if(all.isEmpty())return;
            for(Dimension d:all)d.scheduler.advance(tick);
            long start=System.nanoTime(),budget=750_000;
            int columns=256,visited=0;
            while(visited<all.size()&&visited<4&&columns>0&&System.nanoTime()-start<budget){
                Dimension d=all.get(Math.floorMod(cursor++,all.size()));visited++;
                d.transfer(io);
                long remaining=budget-(System.nanoTime()-start);
                if(remaining<=0)break;
                columns-=d.pump.tick(remaining,columns,1);
            }
            if(maintenance!=null&&maintenance.isDone()){
                try{
                    var result=maintenance.join();
                    if(result.compacted())LOG.info("V4 compacted {} -> {} bytes ({} pages)",result.beforeBytes(),result.afterBytes(),result.pages());
                }catch(CompletionException ex){LOG.warn("V4 geographic maintenance deferred",ex.getCause());}
                maintenance=null;
            }
            if(tick%1200==0&&maintenance==null&&!io.failed()&&io.queued()==0&&System.nanoTime()-start<budget){
                Dimension d=all.get(Math.floorMod(maintenanceCursor++,all.size()));
                maintenance=io.compactResident(d.key);
            }
            if(tick%2==0&&System.nanoTime()-start<budget&&io.queued()<=8){
                Dimension d=all.get(Math.floorMod(archiveCursor++,all.size()));d.archive(io,tick);
            }
        }
        void stop(){
            for(Dimension d:dimensions.values()){d.pump.abandonPartial();d.scheduler.prepareShutdown();}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            try{
                for(Dimension d:dimensions.values()){
                    while(d.scheduler.metrics().inactivePending()>0&&System.nanoTime()<deadline&&!io.failed()){
                        d.transfer(io);
                        if(d.handoff!=null)d.handoff.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                        else break;
                    }
                    if(d.scheduler.metrics().inactivePending()>0)LOG.warn("V4 pending shutdown handoff for {}: {}",d.key,d.scheduler.metrics().inactivePending());
                    d.dirty.closeInput();
                }
            }catch(Exception e){LOG.error("V4 shutdown dirty handoff failed",e);}
            try{if(!io.close(15,TimeUnit.SECONDS))LOG.warn("V4 IO drain timed out");}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            dimensions.clear();
        }
    }
}
