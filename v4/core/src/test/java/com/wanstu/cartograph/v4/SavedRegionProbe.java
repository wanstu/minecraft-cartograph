package com.wanstu.cartograph.v4;
import java.nio.file.*;
import java.util.*;
public final class SavedRegionProbe {
    public static void main(String[] args)throws Exception{
        Path path=Path.of(args[0]);int minY=Integer.parseInt(args[1]),height=Integer.parseInt(args[2]),max=Integer.parseInt(args[3]);
        String[] p=path.getFileName().toString().split("\\.");
        int rx=Integer.parseInt(p[1]),rz=Integer.parseInt(p[2]),ok=0,failed=0,columns=0;
        Set<String> materials=new HashSet<>(),biomes=new HashSet<>(),structureTypes=new TreeSet<>();
        int starts=0;
        long start=System.nanoTime();
        try(var r=new AnvilRegionReader(path,rx,rz)){
            var discovered=r.discover();
            for(var chunk:discovered){
                if(ok+failed>=max)break;
                try{
                    var s=SavedChunkSurface.decode(r.load(chunk).nbt(),chunk.x(),chunk.z(),minY,height);
                    for(var c:s.columns())if(c!=null){columns++;materials.add(c.material());biomes.add(c.biome());}
                    starts+=s.starts().size();for(var structure:s.starts())structureTypes.add(structure.type());
                    ok++;
                }catch(java.io.IOException ex){failed++;if(failed<=5)System.out.println("SKIP "+chunk.x()+","+chunk.z()+": "+ex.getMessage());}
            }
            System.out.printf(Locale.ROOT,"region=%s discovered=%d decoded=%d rejected=%d columns=%d materials=%d biomes=%d seconds=%.3f%n",path.getFileName(),discovered.size(),ok,failed,columns,materials.size(),biomes.size(),(System.nanoTime()-start)/1e9);
            System.out.println("starts="+starts+" structureTypes="+structureTypes);
            if(ok==0)throw new IllegalStateException("No full chunks decoded");
        }
    }
}
