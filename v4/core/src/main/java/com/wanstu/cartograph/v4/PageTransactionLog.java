package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32C;

/** Append-only log; incremental batches force immediately, unpublished fresh shards force before publication. */
final class PageTransactionLog {
    private static final int MAGIC=0x4356344c, END=0x434f4d54;
    private static final int MAX=32*1024*1024;
    private static final int MAX_PAGE=4*1024*1024+32*1024;
    record Pointer(long offset,int length,long revision) {}
    private static final int INDEX_MAGIC=0x43563449, INDEX_MAX=4096;
    private final Path file,indexFile;
    private final Map<RegionPageStore.Key,Pointer> index=new HashMap<>();
    private long flushes,scanBytes;
    private RandomAccessFile staging;
    PageTransactionLog(Path root)throws IOException{
        file=root.resolve("pages.cv4log");indexFile=root.resolve("pages.cv4idx");
        if(Files.notExists(file))Files.createFile(file);
        recover(loadCheckpoint());
    }
    long flushes(){return flushes;}
    long scanBytes(){return scanBytes;}
    long revision(RegionPageStore.Key key){
        Pointer p=index.get(key);return p==null?0:p.revision;
    }
    byte[] read(RegionPageStore.Key key)throws IOException{
        Pointer p=index.get(key);if(p==null)return null;
        try(var in=new RandomAccessFile(file.toFile(),"r")){
            in.seek(p.offset-24);
            if(in.readInt()!=key.lod()||in.readInt()!=key.x()||in.readInt()!=key.z()||
                in.readLong()!=p.revision||in.readInt()!=p.length)throw new IOException("Page pointer identity");
            byte[] data=new byte[p.length];in.readFully(data);return data;
        }
    }
    private static int checksum(byte[] data){
        CRC32C crc=new CRC32C();crc.update(data);return (int)crc.getValue();
    }
    private Map<RegionPageStore.Key,Pointer> parse(byte[] body,long base)throws IOException{
        Map<RegionPageStore.Key,Pointer> pending=new HashMap<>();
        try(var raw=new ByteArrayInputStream(body);var in=new DataInputStream(raw)){
            int count=in.readInt();
            if(count<1||count>DiskGeographicPyramid.MAX_BATCH_CHUNKS*11)throw new IOException("Invalid transaction page count");
            for(int i=0;i<count;i++){
                var key=new RegionPageStore.Key(in.readInt(),in.readInt(),in.readInt());
                long revision=in.readLong();int length=in.readInt();
                if(revision<=revision(key)||length<1||length>MAX_PAGE||length>raw.available())
                    throw new IOException("Invalid transaction page");
                long offset=base+body.length-raw.available();
                if(pending.put(key,new Pointer(offset,length,revision))!=null)throw new IOException("Duplicate page");
                in.skipNBytes(length);
            }
            if(in.read()!=-1)throw new IOException("Trailing transaction data");
        }catch(IllegalArgumentException e){throw new IOException("Invalid transaction key",e);}
        return pending;
    }
    record Encoded(RegionPageStore.Key key,long revision,byte[] data){}
    void append(List<RegionPageStore.Page> pages,List<byte[]> payloads)throws IOException{
        List<Encoded> encoded=new ArrayList<>(pages.size());
        for(int i=0;i<pages.size();i++)encoded.add(new Encoded(pages.get(i).key(),pages.get(i).revision(),payloads.get(i)));
        appendEncoded(encoded);
    }
    void appendEncoded(List<Encoded> pages)throws IOException{
        if(staging!=null)throw new IOException("Staged writer active");
        try(var out=new RandomAccessFile(file.toFile(),"rw")){append(out,pages,true);}
    }
    // Only a private, unpublished fresh-build staging directory may defer force.
    void appendStaged(List<Encoded> pages)throws IOException{
        if(staging==null)staging=new RandomAccessFile(file.toFile(),"rw");
        append(staging,pages,false);
    }
    void forceStaged()throws IOException{
        if(staging==null)staging=new RandomAccessFile(file.toFile(),"rw");
        try{staging.getChannel().force(true);flushes++;}
        finally{closeStaged();}
    }
    void closeStaged()throws IOException{
        if(staging!=null){
            RandomAccessFile out=staging;staging=null;out.close();
        }
    }
    private void append(RandomAccessFile fileOut,List<Encoded> pages,boolean durable)throws IOException{
        if(pages.isEmpty()||pages.size()>DiskGeographicPyramid.MAX_BATCH_CHUNKS*11)
            throw new IOException("Invalid transaction page count");
        long length=4;
        for(Encoded p:pages)length+=24L+p.data.length;
        if(length>MAX)throw new IOException("Transaction limit");
        byte[] body=new byte[(int)length];
        ByteBuffer out=ByteBuffer.wrap(body);
        out.putInt(pages.size());
        long start=fileOut.length();
        Map<RegionPageStore.Key,Pointer> next=new HashMap<>();
        for(Encoded page:pages){
            byte[] data=page.data;var key=page.key;
            if(data.length<1||data.length>MAX_PAGE||page.revision<=revision(key))
                throw new IOException("Invalid transaction page");
            out.putInt(key.lod()).putInt(key.x()).putInt(key.z()).putLong(page.revision).putInt(data.length);
            if(next.put(key,new Pointer(start+8+out.position(),data.length,page.revision))!=null)
                throw new IOException("Duplicate page");
            out.put(data);
        }
        var channel=fileOut.getChannel();channel.position(start);
        writeFully(channel,ByteBuffer.allocate(8).putInt(MAGIC).putInt(body.length).flip());
        writeFully(channel,ByteBuffer.wrap(body));
        writeFully(channel,ByteBuffer.allocate(8).putInt(checksum(body)).putInt(END).flip());
        if(durable){fileOut.getChannel().force(true);flushes++;}
        index.putAll(next);
    }
    private static void writeFully(java.nio.channels.FileChannel channel,ByteBuffer bytes)throws IOException{
        while(bytes.hasRemaining())channel.write(bytes);
    }
    /** Optional derived index, never the authority for recovering uncheckpointed writes.
     *  Only one owner may write or replace this log. Checkpoint pages still verify CRC on read.
     */
    void checkpoint()throws IOException{
        if(staging!=null)throw new IOException("Cannot checkpoint unforced staging");
        if(index.size()>INDEX_MAX)return; // only bounded shard indexes
        long length=Files.size(file);
        if(length==0)return;
        int tail;
        try(var in=new RandomAccessFile(file.toFile(),"r")){
            in.seek(length-8);tail=in.readInt();
            if(in.readInt()!=END)throw new IOException("Checkpoint requires complete log");
        }
        ByteBuffer bytes=ByteBuffer.allocate(24+32*index.size());
        bytes.putInt(INDEX_MAGIC).putInt(1).putLong(length).putInt(tail).putInt(index.size());
        for(var e:index.entrySet()){
            var k=e.getKey();var p=e.getValue();
            bytes.putInt(k.lod()).putInt(k.x()).putInt(k.z()).putLong(p.offset).putInt(p.length).putLong(p.revision);
        }
        byte[] body=bytes.array();
        Path tmp=Files.createTempFile(indexFile.getParent(),".index-",".tmp");
        try{
            try(var out=new RandomAccessFile(tmp.toFile(),"rw")){
                out.write(body);out.writeInt(checksum(body));out.getChannel().force(true);
            }
            Files.move(tmp,indexFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
    private long loadCheckpoint()throws IOException{
        if(!Files.isRegularFile(indexFile))return 0;
        try{
            long indexLength=Files.size(indexFile);
            if(indexLength<28||indexLength>28L+32L*INDEX_MAX)return 0;
            byte[] raw=Files.readAllBytes(indexFile);
            byte[] body=Arrays.copyOf(raw,raw.length-4);
            if(checksum(body)!=ByteBuffer.wrap(raw,raw.length-4,4).getInt())return 0;
            ByteBuffer in=ByteBuffer.wrap(body);
            if(in.getInt()!=INDEX_MAGIC||in.getInt()!=1)return 0;
            long end=in.getLong();int tail=in.getInt(),count=in.getInt();
            if(end<16||end>Files.size(file)||count<0||count>INDEX_MAX||in.remaining()!=count*32)return 0;
            try(var log=new RandomAccessFile(file.toFile(),"r")){
                log.seek(end-8);
                if(log.readInt()!=tail||log.readInt()!=END)return 0;
                Map<RegionPageStore.Key,Pointer> loaded=new HashMap<>();
                for(int i=0;i<count;i++){
                    var key=new RegionPageStore.Key(in.getInt(),in.getInt(),in.getInt());
                    long offset=in.getLong();int length=in.getInt();long rev=in.getLong();
                    if(offset<36||length<1||length>MAX_PAGE||offset>end-8-length||rev<1)return 0;
                    // Bind each pointer to its actual frame's page header; no data payload scan.
                    // Header checks are deferred to individual page reads to avoid random I/O here.
                    if(loaded.put(key,new Pointer(offset,length,rev))!=null)return 0;
                }
                index.putAll(loaded);return end;
            }
        }catch(IllegalArgumentException|java.nio.BufferUnderflowException e){return 0;}
    }
    long entryRevision(RegionPageStore.Key key){return revision(key);}
    private void recover(long start)throws IOException{
        try(var in=new RandomAccessFile(file.toFile(),"rw")){
            long size=in.length(),pos=start;
            while(pos<size){
                in.seek(pos);
                if(size-pos<8){in.setLength(pos);in.getChannel().force(true);break;}
                if(in.readInt()!=MAGIC)throw new IOException("Transaction magic");
                int length=in.readInt();
                if(length<4||length>MAX)throw new IOException("Transaction length");
                long end=pos+16L+length;
                if(end>size){in.setLength(pos);in.getChannel().force(true);break;}
                byte[] body=new byte[length];in.readFully(body);
                if(in.readInt()!=checksum(body)||in.readInt()!=END)throw new IOException("Transaction checksum");
                index.putAll(parse(body,pos+8));scanBytes+=16L+length;pos=end;
            }
        }
    }
}
