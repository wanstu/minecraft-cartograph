package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32C;

/** Append-only transaction experiment. One durable flush per batch; direct latest-page index. */
final class PageTransactionLog {
    private static final int MAGIC=0x4356344c, END=0x434f4d54;
    private static final int MAX=32*1024*1024;
    private static final int MAX_PAGE=4*1024*1024+32*1024;
    record Pointer(long offset,int length,long revision) {}
    private final Path file;
    private final Map<RegionPageStore.Key,Pointer> index=new HashMap<>();
    private long flushes,scanBytes;
    PageTransactionLog(Path root)throws IOException{
        file=root.resolve("pages.cv4log");
        if(Files.notExists(file))Files.createFile(file);
        recover();
    }
    long flushes(){return flushes;}
    long scanBytes(){return scanBytes;}
    long revision(RegionPageStore.Key key){
        Pointer p=index.get(key);return p==null?0:p.revision;
    }
    byte[] read(RegionPageStore.Key key)throws IOException{
        Pointer p=index.get(key);if(p==null)return null;
        try(var in=new RandomAccessFile(file.toFile(),"r")){
            in.seek(p.offset);byte[] data=new byte[p.length];in.readFully(data);return data;
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
    void append(List<RegionPageStore.Page> pages,List<byte[]> payloads)throws IOException{
        long length=4;
        for(byte[] data:payloads)length+=24L+data.length;
        if(length>MAX)throw new IOException("Transaction limit");
        byte[] body=new byte[(int)length];
        ByteBuffer out=ByteBuffer.wrap(body);
        out.putInt(pages.size());
        try(var fileOut=new RandomAccessFile(file.toFile(),"rw")){
            long start=fileOut.length();
            Map<RegionPageStore.Key,Pointer> next=new HashMap<>();
            for(int i=0;i<pages.size();i++){
                var page=pages.get(i);byte[] data=payloads.get(i);var key=page.key();
                if(data.length<1||data.length>MAX_PAGE||page.revision()<=revision(key))
                    throw new IOException("Invalid transaction page");
                out.putInt(key.lod()).putInt(key.x()).putInt(key.z()).putLong(page.revision()).putInt(data.length);
                if(next.put(key,new Pointer(start+8+out.position(),data.length,page.revision()))!=null)
                    throw new IOException("Duplicate page");
                out.put(data);
            }
            fileOut.seek(start);fileOut.writeInt(MAGIC);fileOut.writeInt(body.length);fileOut.write(body);
            fileOut.writeInt(checksum(body));fileOut.writeInt(END);
            fileOut.getChannel().force(true);flushes++;
            index.putAll(next);
        }
    }
    private void recover()throws IOException{
        try(var in=new RandomAccessFile(file.toFile(),"rw")){
            long size=in.length(),pos=0;
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
