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
        NeoForge.EVENT_BUS.addListener(CartographV4Dev::beforeTick);
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
            int min=level.getMinBuildHeight(),y=pos.getY();
            if(SurfaceChangeFilter.irrelevant(y,top,relief,floor,min,false))return;
            // Only water uses the ocean-floor height in the captured facts. For a
            // known dry top surface, changes far below both other heightmaps cannot
            // change any mapped channel. Uncertain/modded map colors stay conservative.
            if(SurfaceChangeFilter.belowSurface(y,top,relief,min)){
                try{
                    var surfacePos=new BlockPos(pos.getX(),top,pos.getZ());
                    var state=chunk.getBlockState(surfacePos);
                    var id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if(id.getNamespace().equals("minecraft")&&state.getMapColor(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,surfacePos)
                        !=net.minecraft.world.level.material.MapColor.WATER)return;
                }catch(RuntimeException ignored){/* fall back to dirty capture */}
            }
        }
        ChunkPos p=chunk.getPos();onServer(level,r->r.dimension(level).scheduler.surfaceChanged(p.x,p.z));
    }
    private static void beforeTick(ServerTickEvent.Pre e){
        Runtime current=runtime;if(current!=null&&current.server==e.getServer())current.tickStarted=System.nanoTime();
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
        final ArchiveBackoff archiveBackoff=new ArchiveBackoff();
        final ServerLevel level;
        int focusCursor;
        void prioritizePlayers(){
            var players=level.players();
            if(players.isEmpty()){scheduler.focus(null);return;}
            var p=players.get(Math.floorMod(focusCursor++,players.size())).chunkPosition();
            scheduler.focus(new ActiveChunkScheduler.Position(p.x,p.z));
        }
        Dimension(ServerLevel level,WorldIoService io){
            this.level=level;
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
        void archive(WorldIoService io,long tick,int max){
            if(archive!=null){
                if(!archive.isDone())return;
                try{
                    var next=archive.join();
                    boolean madeProgress=progress==null||next.committed()>progress.committed()||next.unchanged()>progress.unchanged();
                    archiveBackoff.completed(tick,next.discovered(),next.pending()>next.waitingActive(),madeProgress,progress!=null&&next.sourceErrors()>progress.sourceErrors());
                    progress=next;
                }catch(CompletionException e){archiveBackoff.failed(tick);}
                archive=null;
            }
            if(io.failed()||io.queued()>8||!archiveBackoff.due(tick,gate.hasWakeups()))return;
            archiveBackoff.submitted(tick);
            archive=io.archiveStep(key,regions,minY,height,MinecraftSurfaceFacts::fromSaved,max);
        }
        void transfer(WorldIoService io){
            scheduler.drainInactiveAsync(16,dirty);
            if(handoff!=null&&handoff.isDone()){
                try{if(handoff.join()>0)archiveBackoff.wake();}
                catch(CompletionException ignored){/* scheduler retains failed receipts */}
                handoff=null;
            }
            if(dirty.size()>0&&handoff==null&&!io.failed())handoff=io.handoff(key,dirty);
        }
    }
    private static final class Runtime{
        final MinecraftServer server;
        final WorldIoService io;
        final Map<ServerLevel,Dimension> dimensions=new LinkedHashMap<>();
        int cursor,archiveCursor,maintenanceCursor;
        CompletableFuture<RegionPageStore.Compaction> maintenance;
        long tick,tickStarted;
        boolean ioFailureLogged;
        final CaptureWorkBudget work=new CaptureWorkBudget();
        Runtime(MinecraftServer server){
            this.server=server;
            io=new WorldIoService(server.getWorldPath(LevelResource.ROOT).resolve("cartograph-v4-dev"),3,128);
        }
        Dimension dimension(ServerLevel level){return dimensions.computeIfAbsent(level,l->new Dimension(l,io));}
        void tick(){
            tick++;
            if(io.failed()&&!ioFailureLogged){ioFailureLogged=true;LOG.error("V4 geographic IO failed; writes suspended",io.failureCause());}
            if(tickStarted!=0){work.sample(Math.max(0,System.nanoTime()-tickStarted),io.queued());tickStarted=0;}
            if(tick%200==0)LOG.info("V4 collection pressure {} | columns {} | archive batch {} | queued {}",work.level(),work.columns(),work.archiveBatch(),io.queued());
            if(tick%200==0)for(Dimension d:dimensions.values())LOG.info("V4 capture {}: {} / {}",d.key,d.scheduler.metrics(),d.pump.metrics());
            if(tick%200==0)for(Dimension d:dimensions.values())if(d.progress!=null)LOG.info("V4 archive {}: {}",d.key,d.progress);
            if(tick==1||tick%200==0)for(ServerLevel level:server.getAllLevels())dimension(level);
            List<Dimension> all=new ArrayList<>(dimensions.values());
            if(all.isEmpty())return;
            for(Dimension d:all)d.scheduler.advance(tick);
            long start=System.nanoTime(),budget=work.nanos();
            int columns=work.columns(),visited=0;
            while(visited<all.size()&&visited<4&&columns>0&&System.nanoTime()-start<budget){
                Dimension d=all.get(Math.floorMod(cursor++,all.size()));visited++;
                d.transfer(io);
                d.prioritizePlayers();
                long remaining=budget-(System.nanoTime()-start);
                if(remaining<=0)break;
                // Drain receipts even if sampling is temporarily blocked by IO pressure.
                if(io.failed()||io.queued()>=32)d.pump.tick(remaining,0,0);
                else columns-=d.pump.tick(remaining,columns,1);
            }
            if(maintenance!=null&&maintenance.isDone()){
                try{
                    var result=maintenance.join();
                    if(result.compacted())LOG.info("V4 compacted {} -> {} bytes ({} pages)",result.beforeBytes(),result.afterBytes(),result.pages());
                }catch(CompletionException ex){LOG.warn("V4 geographic maintenance deferred",ex.getCause());}
                maintenance=null;
            }
            if(tick%1200==0&&work.maintenanceAllowed()&&maintenance==null&&!io.failed()&&io.queued()==0&&System.nanoTime()-start<budget){
                Dimension d=all.get(Math.floorMod(maintenanceCursor++,all.size()));
                maintenance=io.compactResident(d.key);
            }
            if(work.archiveDue(tick,io.queued())&&!io.failed()&&System.nanoTime()-start<budget){
                Dimension d=all.get(Math.floorMod(archiveCursor++,all.size()));d.archive(io,tick,work.archiveBatch());
            }
        }
        void stop(){
            for(Dimension d:dimensions.values()){d.pump.abandonPartial();d.scheduler.prepareShutdown();}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            try{
                for(Dimension d:dimensions.values()){
                    while(d.scheduler.metrics().inactivePending()>0&&System.nanoTime()<deadline&&!io.failed()){
                        d.transfer(io);
                        // A turn can consume only completed receipts without
                        // submitting a new batch. Continue until every pending
                        // revision is examined, not just until the future is null.
                        if(d.handoff!=null)d.handoff.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                    }
                    if(d.scheduler.metrics().inactivePending()>0)LOG.warn("V4 pending shutdown handoff for {}: {}",d.key,d.scheduler.metrics().inactivePending());
                    d.dirty.closeInput();
                }
            }catch(Exception e){LOG.error("V4 shutdown dirty handoff failed",e);}
            try{if(!io.close(15,TimeUnit.SECONDS)){
                if(io.failed())LOG.error("V4 geographic IO shutdown failed",io.failureCause());
                else LOG.warn("V4 IO drain timed out");
            }}
            catch(InterruptedException e){Thread.currentThread().interrupt();}
            dimensions.clear();
        }
    }
}
