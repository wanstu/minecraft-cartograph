package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** A derived, bounded region-local index of compressed source fingerprints.
 * A hit also requires the actual durable leaf-page revision to match.
 * Commit geography first; an interrupted index write can only cause extra verification.
 */
final class SourceFingerprints {
    private static final int MAGIC=0x43563453,VERSION=1,N=1024,STRIDE=40,SIZE=16+N*STRIDE+4;
    record Region(int x,int z){
        static Region of(int x,int z){return new Region(Math.floorDiv(x,32),Math.floorDiv(z,32));}
        Path file(Path root){return root.resolve("r."+x+"."+z+".cv4src");}
    }
    private static final class Page{
        final long[] revisions=new long[N];
        final byte[][] hashes=new byte[N][];
        boolean dirty;
    }
    private final Path root;
    private final LinkedHashMap<Region,Page> cache=new LinkedHashMap<>(16,.75f,true);
    SourceFingerprints(Path root)throws IOException{this.root=root;Files.createDirectories(root);}
    private static int slot(int x,int z){return Math.floorMod(z,32)*32+Math.floorMod(x,32);}
    private Page page(Region key)throws IOException{
        Page p=cache.get(key);if(p!=null)return p;
        if(cache.size()>=8){
            var it=cache.entrySet().iterator();var oldest=it.next();
            flush(oldest.getKey(),oldest.getValue());it.remove();
        }
        p=new Page();Path path=key.file(root);
        if(Files.isRegularFile(path)&&Files.size(path)==SIZE){
            byte[] data=Files.readAllBytes(path);ByteBuffer b=ByteBuffer.wrap(data);
            if(data.length==SIZE&&b.getInt()==MAGIC&&b.getInt()==VERSION&&b.getInt()==key.x&&b.getInt()==key.z
                &&b.getInt(SIZE-4)==crc(data,SIZE-4)){
                for(int i=0;i<N;i++){
                    long revision=b.getLong();byte[] hash=new byte[32];b.get(hash);
                    if(revision>0){p.revisions[i]=revision;p.hashes[i]=hash;}
                }
            }
        }
        cache.put(key,p);return p;
    }
    boolean matches(int x,int z,long revision,byte[] hash)throws IOException{
        if(revision<=0)return false;
        Page p=page(Region.of(x,z));int i=slot(x,z);
        return p.revisions[i]==revision&&Arrays.equals(p.hashes[i],hash);
    }
    void record(int x,int z,long revision,byte[] hash)throws IOException{
        if(revision<=0||hash.length!=32)return;
        Page p=page(Region.of(x,z));int i=slot(x,z);
        if(p.revisions[i]==revision&&Arrays.equals(p.hashes[i],hash))return;
        p.revisions[i]=revision;p.hashes[i]=hash.clone();p.dirty=true;
    }
    void flush()throws IOException{for(var e:cache.entrySet())flush(e.getKey(),e.getValue());}
    private void flush(Region key,Page p)throws IOException{
        if(!p.dirty)return;
        byte[] data=new byte[SIZE];ByteBuffer b=ByteBuffer.wrap(data);
        b.putInt(MAGIC).putInt(VERSION).putInt(key.x).putInt(key.z);
        byte[] zero=new byte[32];
        for(int i=0;i<N;i++)b.putLong(p.revisions[i]).put(p.hashes[i]==null?zero:p.hashes[i]);
        b.putInt(crc(data,SIZE-4));
        Path tmp=Files.createTempFile(root,".source-",".tmp");
        try{
            try(var out=new RandomAccessFile(tmp.toFile(),"rw")){out.write(data);out.getChannel().force(true);}
            Files.move(tmp,key.file(root),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            p.dirty=false;
        }finally{Files.deleteIfExists(tmp);}
    }
    private static int crc(byte[] data,int length){CRC32C c=new CRC32C();c.update(data,0,length);return (int)c.getValue();}
}
