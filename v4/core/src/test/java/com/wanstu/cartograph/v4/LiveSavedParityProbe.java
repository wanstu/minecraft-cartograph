package com.wanstu.cartograph.v4;
import java.nio.file.*;
/** Read-only smoke verification against the isolated 1.21.1 dev server world. */
public final class LiveSavedParityProbe {
    public static void main(String[] args)throws Exception{
        Path world=Path.of(args[0]);int x=Integer.parseInt(args[1]),z=Integer.parseInt(args[2]);
        String expected=args[3];int expectedY=Integer.parseInt(args[4]);
        int cx=Math.floorDiv(x,16),cz=Math.floorDiv(z,16),rx=Math.floorDiv(cx,32),rz=Math.floorDiv(cz,32);
        String dim=java.util.HexFormat.of().formatHex("minecraft:overworld".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var shard=new RegionShard(world.resolve("cartograph-v4-dev").resolve(dim),RegionShard.Id.ofChunk(cx,cz),8,4*1024*1024);
        var live=shard.get(new GeographicPyramid.Cell(0,x,z));
        try(var reader=new AnvilRegionReader(world.resolve("region/r."+rx+"."+rz+".mca"),rx,rz)){
            var saved=SavedChunkSurface.decode(reader.load(reader.identity(cx,cz)).nbt(),cx,cz,-64,384).columns()[Math.floorMod(z,16)*16+Math.floorMod(x,16)];
            if(live==null||saved==null||!live.feature().materialKey().equals(expected)||!saved.material().equals(expected)||
                saved.surfaceY()!=expectedY||saved.surfaceY()!=live.stats().surfaceSum()||!saved.biome().equals(live.feature().biomeKey()))
                throw new AssertionError("Live/saved parity");
            System.out.println("PASS: "+saved+" matches persisted live facts");
        }
    }
}
