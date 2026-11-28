package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Read-only modern Anvil region access. No Minecraft chunk tickets or world writes. */
public final class AnvilRegionReader implements AutoCloseable {
    public static final int SECTOR=4096, SLOTS=1024, MAX_NBT=32*1024*1024;
    public record SavedChunk(int x,int z,int sector,int sectors,long timestamp){}
    public record Payload(SavedChunk identity,byte[] nbt){}
    public record Encoded(SavedChunk identity,int codec,byte[] bytes){
        public Encoded{bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
        public byte[] fingerprint(){
            try{var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update((byte)codec);return digest.digest(bytes);}
            catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
        }
        public Payload decode()throws IOException{
            try(InputStream raw=new ByteArrayInputStream(bytes);InputStream decoded=switch(codec){
                case 1 -> new GZIPInputStream(raw);case 2 -> new InflaterInputStream(raw);case 3 -> raw;
                default -> throw new IOException("Unsupported Anvil compression "+codec);
            }){return new Payload(identity,limited(decoded,MAX_NBT));}
        }
    }
    private final Path path;
    private final int rx,rz;
    private final FileChannel channel;
    public AnvilRegionReader(Path path,int rx,int rz)throws IOException{
        this.path=path;this.rx=rx;this.rz=rz;
        if(!path.getFileName().toString().equals("r."+rx+"."+rz+".mca"))throw new IllegalArgumentException("Region filename");
        Math.multiplyExact(rx,32);Math.multiplyExact(rz,32);
        channel=FileChannel.open(path,StandardOpenOption.READ);
    }
    private void read(ByteBuffer b,long offset)throws IOException{
        while(b.hasRemaining()){
            int n=channel.read(b,offset);
            if(n<=0)throw new EOFException("Incomplete region");
            offset+=n;
        }
        b.flip();
    }
    private SavedChunk entry(int slot,int location,int timestamp)throws IOException{
        if(location==0)return null;
        int sector=location>>>8,sectors=location&255;
        long size=channel.size(),start=(long)sector*SECTOR,end=(sector+(long)sectors)*SECTOR;
        // Vanilla may leave the final allocated sector unpadded until closing the file.
        // Allow only the missing tail padding, never missing payload bytes or whole sectors.
        if(sector<2||sectors==0||start+5>size||end-size>=SECTOR)
            throw new IOException("Invalid Anvil sector range");
        if(end>size){
            ByteBuffer prefix=ByteBuffer.allocate(5);read(prefix,start);
            int length=prefix.getInt();
            if(length<1||length>sectors*(long)SECTOR-4||start+4L+length>size)
                throw new IOException("Incomplete Anvil tail payload");
        }
        return new SavedChunk(rx*32+slot%32,rz*32+slot/32,sector,sectors,Integer.toUnsignedLong(timestamp));
    }
    public List<SavedChunk> discover()throws IOException{
        ByteBuffer header=ByteBuffer.allocate(8192);read(header,0);
        List<SavedChunk> result=new ArrayList<>();
        BitSet occupied=new BitSet();
        for(int i=0;i<SLOTS;i++){
            SavedChunk e=entry(i,header.getInt(i*4),header.getInt(4096+i*4));
            if(e==null)continue;
            int overlap=occupied.nextSetBit(e.sector);
            if(overlap>=0&&overlap<e.sector+e.sectors)throw new IOException("Overlapping Anvil chunks");
            occupied.set(e.sector,e.sector+e.sectors);result.add(e);
        }
        return List.copyOf(result);
    }
    public SavedChunk identity(int x,int z)throws IOException{
        if(Math.floorDiv(x,32)!=rx||Math.floorDiv(z,32)!=rz)throw new IllegalArgumentException("Outside region");
        int slot=Math.floorMod(z,32)*32+Math.floorMod(x,32);
        ByteBuffer b=ByteBuffer.allocate(4);read(b,slot*4L);int location=b.getInt();
        b.clear();read(b,4096L+slot*4L);
        return entry(slot,location,b.getInt());
    }
    /** Caller must arbitrate against live capture; identity alone is not a content version. */
    public Payload load(SavedChunk expected)throws IOException{return encoded(expected).decode();}
    public Encoded encoded(SavedChunk expected)throws IOException{
        if(!expected.equals(identity(expected.x,expected.z)))throw new IOException("Region identity changed");
        long offset=(long)expected.sector*SECTOR;
        ByteBuffer h=ByteBuffer.allocate(5);read(h,offset);
        int length=h.getInt(),compression=h.get()&255;
        if(length<1||length>expected.sectors*(long)SECTOR-4)throw new IOException("Invalid chunk length");
        boolean external=(compression&128)!=0;
        int codec=compression&127;
        byte[] compressed;
        if(external){
            if(length!=1)throw new IOException("Invalid external chunk marker");
            Path ext=path.resolveSibling("c."+expected.x+"."+expected.z+".mcc");
            try(InputStream input=Files.newInputStream(ext)){compressed=limited(input,MAX_NBT);}
        }else{
            ByteBuffer data=ByteBuffer.allocate(length-1);read(data,offset+5);compressed=data.array();
        }
        if(!expected.equals(identity(expected.x,expected.z)))throw new IOException("Region changed while reading");
        if(codec<1||codec>3)throw new IOException("Unsupported Anvil compression "+codec);
        return new Encoded(expected,codec,compressed);
    }
    private static byte[] limited(InputStream input,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];int n;
        while((n=input.read(buffer))!=-1){
            if(n>limit-out.size())throw new IOException("Chunk NBT exceeds limit");
            out.write(buffer,0,n);
        }
        return out.toByteArray();
    }
    @Override public void close()throws IOException{channel.close();}
}
