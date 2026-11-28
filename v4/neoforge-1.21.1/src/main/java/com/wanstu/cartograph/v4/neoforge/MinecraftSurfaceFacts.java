package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import java.io.IOException;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import java.util.*;

final class MinecraftSurfaceFacts {
    private MinecraftSurfaceFacts(){}
    private static final int[] LINEAR=new int[256];
    static{
        for(int i=0;i<256;i++){
            double v=i/255.0;
            LINEAR[i]=(int)Math.round(65535*(v<=0.04045?v/12.92:Math.pow((v+0.055)/1.055,2.4)));
        }
    }
    private static <T extends Comparable<T>> String valueName(Property<T> property,Comparable<?> value){
        @SuppressWarnings("unchecked") T v=(T)value;
        return property.getName(v);
    }
    static String material(BlockState state){
        String name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if(state.getValues().isEmpty())return name;
        TreeMap<String,String> properties=new TreeMap<>();
        state.getValues().forEach((p,v)->properties.put(p.getName(),valueName(p,v)));
        StringJoiner join=new StringJoiner(",",name+"[","]");
        properties.forEach((k,v)->join.add(k+"="+v));
        return join.toString();
    }
    private static <T extends Comparable<T>> BlockState property(BlockState state,Property<T> p,String value)throws IOException{
        var parsed=p.getValue(value);
        if(parsed.isEmpty())throw new IOException("Unknown block property "+p.getName()+"="+value);
        return state.setValue(p,parsed.get());
    }
    /** Resolve the exact saved state, never substitute a guessed material. */
    static BlockState resolve(String key)throws IOException{
        try{
            int start=key.indexOf('[');
            String name=start<0?key:key.substring(0,start);
            var id=ResourceLocation.parse(name);
            if(!BuiltInRegistries.BLOCK.containsKey(id))throw new IOException("Unknown saved block "+name);
            var block=BuiltInRegistries.BLOCK.get(id);
            BlockState state=block.defaultBlockState();
            if(start>=0){
                if(!key.endsWith("]"))throw new IOException("Invalid saved state "+key);
                for(String pair:key.substring(start+1,key.length()-1).split(",")){
                    int eq=pair.indexOf('=');
                    if(eq<1)throw new IOException("Invalid saved property "+pair);
                    Property<?> p=block.getStateDefinition().getProperty(pair.substring(0,eq));
                    if(p==null)throw new IOException("Unknown saved property "+pair);
                    state=property(state,p,pair.substring(eq+1));
                }
            }
            if(!material(state).equals(key))throw new IOException("Noncanonical or incomplete saved state "+key);
            return state;
        }catch(IllegalArgumentException e){throw new IOException("Invalid saved state "+key,e);}
    }
    /**
     * Frozen-save mapping. Registry must already be initialized and stable.
     * Uses the same raw top-block facts, base MapColor and height semantics as live capture.
     */
    static FreshRegionBuilder.Columns fromSaved(SavedChunkSurface.Snapshot snapshot)throws IOException{
        var features=new FeatureDictionary.Feature[256];
        int[] surface=new int[256],ground=new int[256];
        char[] depth=new char[256],red=new char[256],green=new char[256],blue=new char[256];
        var states=new HashMap<String,BlockState>();
        var pos=new BlockPos.MutableBlockPos();
        var columns=snapshot.columns();
        for(int i=0;i<256;i++){
            var c=columns[i];if(c==null)continue;
            BlockState state=states.get(c.material());
            if(state==null){state=resolve(c.material());states.put(c.material(),state);}
            pos.set(snapshot.x()*16+(i&15),c.surfaceY(),snapshot.z()*16+(i>>>4));
            final MapColor color;
            try{color=state.getMapColor(EmptyBlockGetter.INSTANCE,pos);}
            catch(RuntimeException e){throw new IOException("Unsupported saved block color "+c.material(),e);}
            features[i]=new FeatureDictionary.Feature(c.material(),c.biome(),terrain(color));
            surface[i]=c.surfaceY();ground[i]=c.reliefY();
            depth[i]=(char)(color==MapColor.WATER&&c.oceanFloorY()!=GeographicPyramid.UNKNOWN_HEIGHT?
                Math.max(0,c.surfaceY()-c.oceanFloorY()):0);
            int rgb=color.col;
            red[i]=(char)LINEAR[(rgb>>>16)&255];green[i]=(char)LINEAR[(rgb>>>8)&255];blue[i]=(char)LINEAR[rgb&255];
        }
        return new FreshRegionBuilder.Columns(features,surface,ground,depth,red,green,blue);
    }
    static CapturePump.Capture begin(ServerLevel level,CapturePump.Ticket ticket){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Server thread required");
        LevelChunk chunk=level.getChunkSource().getChunkNow(ticket.chunkX(),ticket.chunkZ());
        if(chunk==null)return null;
        return new CapturePump.Capture(){
            final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
            final IdentityHashMap<BlockState,String> keys=new IdentityHashMap<>();
            final Map<ResourceLocation,String> biomes=new HashMap<>();
            final CaptureFactCache facts=new CaptureFactCache();
            public boolean valid(){return level.getChunkSource().getChunkNow(ticket.chunkX(),ticket.chunkZ())==chunk;}
            public RegionPageStore.Entry column(int i){
                int x=i&15,z=i>>>4,min=level.getMinBuildHeight();
                int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
                if(y<min)return null;
                int relief=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
                int floor=chunk.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z);
                pos.set(ticket.chunkX()*16+x,y,ticket.chunkZ()*16+z);
                BlockState state=chunk.getBlockState(pos);
                // Raw quart biome from the observed chunk, exactly as stored in its NBT.
                // Level.getBiome interpolates across neighboring quart cells and may
                // disagree with a frozen save even when the terrain has not changed.
                String biome=biomes.computeIfAbsent(chunk.getNoiseBiome(Math.floorDiv(pos.getX(),4),
                    Math.floorDiv(y,4),Math.floorDiv(pos.getZ(),4)).unwrapKey().orElseThrow().location(),ResourceLocation::toString);
                String material=keys.computeIfAbsent(state,MinecraftSurfaceFacts::material);
                MapColor color=state.getMapColor(EmptyBlockGetter.INSTANCE,pos);
                int depth=color==MapColor.WATER&&floor>=min?Math.max(0,y-floor):0;
                var terrain=terrain(color);
                int rgb=color.col;
                return facts.entry(material,biome,terrain,y,
                    relief<min?GeographicPyramid.UNKNOWN_HEIGHT:relief,depth,
                    LINEAR[(rgb>>>16)&255],LINEAR[(rgb>>>8)&255],LINEAR[rgb&255]);
            }
        };
    }
    private static FeatureDictionary.Terrain terrain(MapColor c){
        if(c==MapColor.WATER)return FeatureDictionary.Terrain.WATER;
        if(c==MapColor.GRASS)return FeatureDictionary.Terrain.GRASS;
        if(c==MapColor.PLANT)return FeatureDictionary.Terrain.FOREST;
        if(c==MapColor.STONE)return FeatureDictionary.Terrain.ROCK;
        if(c==MapColor.SAND)return FeatureDictionary.Terrain.SAND;
        if(c==MapColor.SNOW||c==MapColor.ICE)return FeatureDictionary.Terrain.SNOW;
        return FeatureDictionary.Terrain.OTHER;
    }
}
