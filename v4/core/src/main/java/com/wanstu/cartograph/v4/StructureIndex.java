package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Sparse authoritative start-chunk index. No seed, references, or chunk generation. */
public final class StructureIndex {
    // Index ownership always follows the start chunk, not the presentation center.
    public static final int CENTER_RADIUS=512;
    public record Start(String type,int chunkX,int chunkZ,int x,int z,boolean exactCenter){
        public Start(String type,int chunkX,int chunkZ){this(type,chunkX,chunkZ,chunkX*16+8,chunkZ*16+8,false);}
        public Start{
            if(type==null||type.length()>256||!type.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||
                Math.abs((long)chunkX)>1875000||Math.abs((long)chunkZ)>1875000||
                Math.abs((long)x-(chunkX*16L+8))>CENTER_RADIUS||Math.abs((long)z-(chunkZ*16L+8))>CENTER_RADIUS||
                (!exactCenter&&(x!=chunkX*16+8||z!=chunkZ*16+8)))throw new IllegalArgumentException("Structure start");
        }
    }
    /** Union of observed piece bounds, never a seed estimate or a terrain-adapted start box. */
    public static final class Footprint {
        private int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        private int count;
        private boolean invalid;
        public void add(int x0,int z0,int x1,int z1){
            if(x0>x1||z0>z1||Math.abs((long)x0)>30000000||Math.abs((long)x1)>30000000||
                Math.abs((long)z0)>30000000||Math.abs((long)z1)>30000000||++count>4096){invalid=true;return;}
            minX=Math.min(minX,x0);minZ=Math.min(minZ,z0);maxX=Math.max(maxX,x1);maxZ=Math.max(maxZ,z1);
        }
        public void invalidate(){invalid=true;}
        public Start finish(String type,int chunkX,int chunkZ){
            if(!invalid&&count>0){
                int x=(int)Math.floorDiv((long)minX+maxX,2),z=(int)Math.floorDiv((long)minZ+maxZ,2);
                if(Math.abs((long)x-(chunkX*16L+8))<=CENTER_RADIUS&&Math.abs((long)z-(chunkZ*16L+8))<=CENTER_RADIUS)
                    return new Start(type,chunkX,chunkZ,x,z,true);
            }
            return new Start(type,chunkX,chunkZ);
        }
    }
    public record Bounds(int minX,int minZ,int maxX,int maxZ){
        public Bounds{
            if(minX>maxX||minZ>maxZ||Math.abs((long)minX)>1875000||Math.abs((long)maxX)>1875000||
                Math.abs((long)minZ)>1875000||Math.abs((long)maxZ)>1875000)throw new IllegalArgumentException("Bounds");
        }
        public boolean bounded(){return (long)(Math.floorDiv(maxX,32)-Math.floorDiv(minX,32)+1)*(Math.floorDiv(maxZ,32)-Math.floorDiv(minZ,32)+1)<=64;}
        boolean contains(Start s){int x=Math.floorDiv(s.x,16),z=Math.floorDiv(s.z,16);return x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ;}
    }
    public record Result(List<Start> starts,boolean limited){public Result{starts=List.copyOf(starts);}}
    private static final int MAGIC=0x43563454,MAX=16384,MAX_BYTES=8*1024*1024;
    private static final Comparator<Start> ORDER=Comparator.comparingInt(Start::chunkX).thenComparingInt(Start::chunkZ).thenComparing(Start::type);
    private record Region(int x,int z){}
    private final Path root;
    // Shared IO owner only. Bound decoded regional lists, including empty ones.
    private final LinkedHashMap<Region,List<Start>> cache=new LinkedHashMap<>(16,.75f,true);
    public StructureIndex(Path root){this.root=Objects.requireNonNull(root);}
    private Path file(Region r){return root.resolve("r."+r.x+"."+r.z+".cv4s");}
    private static Region region(int x,int z){return new Region(Math.floorDiv(x,32),Math.floorDiv(z,32));}
    private List<Start> read(Region r)throws IOException{
        var hit=cache.get(r);if(hit!=null)return hit;
        Path p=file(r);List<Start> out=new ArrayList<>();
        if(Files.exists(p)){
            long n=Files.size(p);if(n<12||n>MAX_BYTES)throw new IOException("Structure index length");
            byte[] bytes=Files.readAllBytes(p);CRC32C crc=new CRC32C();crc.update(bytes,0,bytes.length-4);
            try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
                if(in.readInt()!=MAGIC)throw new IOException("Structure format");
                int count=in.readInt();if(count<0||count>MAX)throw new IOException("Structure count");
                for(int i=0;i<count;i++){
                    var s=new Start(in.readUTF(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readBoolean());
                    if(!region(s.chunkX,s.chunkZ).equals(r)||(!out.isEmpty()&&ORDER.compare(out.getLast(),s)>=0))throw new IOException("Structure region/order");
                    out.add(s);
                }
                if(in.readInt()!=(int)crc.getValue()||in.available()!=0)throw new IOException("Structure checksum");
            }catch(IllegalArgumentException ex){throw new IOException("Structure data",ex);}
        }
        List<Start> result=List.copyOf(out);remember(r,result);return result;
    }
    private void remember(Region r,List<Start> data){
        cache.put(r,data);while(cache.size()>8){var it=cache.keySet().iterator();it.next();it.remove();}
    }
    public boolean matches(int x,int z,List<Start> starts)throws IOException{
        var actual=new TreeSet<>(ORDER);
        for(var s:read(region(x,z)))if(s.chunkX==x&&s.chunkZ==z)actual.add(s);
        var expected=new TreeSet<>(ORDER);expected.addAll(starts);
        return List.copyOf(actual).equals(List.copyOf(expected));
    }
    public boolean replace(int x,int z,List<Start> starts)throws IOException{
        if(starts.size()>128)throw new IllegalArgumentException("Too many starts in chunk");
        var r=region(x,z);var old=read(r);
        var next=new TreeSet<>(ORDER);
        for(var s:old)if(s.chunkX!=x||s.chunkZ!=z)next.add(s);
        for(var s:starts){if(s.chunkX!=x||s.chunkZ!=z)throw new IllegalArgumentException("Wrong start chunk");next.add(s);}
        var result=List.copyOf(next);if(result.equals(old))return false;
        if(result.size()>MAX)throw new IOException("Structure region limit");
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(result.size());
            for(var s:result){out.writeUTF(s.type);out.writeInt(s.chunkX);out.writeInt(s.chunkZ);out.writeInt(s.x);out.writeInt(s.z);out.writeBoolean(s.exactCenter);}
            out.flush();byte[] data=bytes.toByteArray();CRC32C crc=new CRC32C();crc.update(data);out.writeInt((int)crc.getValue());
        }
        if(bytes.size()>MAX_BYTES)throw new IOException("Structure region bytes");
        Files.createDirectories(root);Path temp=Files.createTempFile(root,".starts-",".tmp");
        try{
            try(var out=new FileOutputStream(temp.toFile())){bytes.writeTo(out);out.getChannel().force(true);}
            Files.move(temp,file(r),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
        remember(r,result);return true;
    }
    public Result query(Bounds b)throws IOException{
        if(!b.bounded())return new Result(List.of(),true);
        List<Start> out=new ArrayList<>();
        // One 512-block owner-region halo covers every accepted displaced center.
        for(int z=Math.floorDiv(b.minZ,32)-1;z<=Math.floorDiv(b.maxZ,32)+1;z++)
            for(int x=Math.floorDiv(b.minX,32)-1;x<=Math.floorDiv(b.maxX,32)+1;x++)
                for(var s:read(new Region(x,z)))if(b.contains(s)){
                    if(out.size()>=2048)return new Result(out,true);out.add(s);
                }
        return new Result(out,false);
    }
}
