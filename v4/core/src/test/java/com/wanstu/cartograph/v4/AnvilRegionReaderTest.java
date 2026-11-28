package com.wanstu.cartograph.v4;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
public final class AnvilRegionReaderTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    static void reject(IO action)throws Exception{try{action.run();throw new AssertionError("accepted invalid region");}catch(IOException expected){}}
    interface IO{void run()throws Exception;}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("cartograph-anvil-");
        try{
            Path path=dir.resolve("r.-1.2.mca");
            byte[] nbt={10,0,0,0};
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(var z=new DeflaterOutputStream(buffer)){z.write(nbt);}
            byte[] compressed=buffer.toByteArray();
            try(var out=new RandomAccessFile(path.toFile(),"rw")){
                out.setLength(5*4096);
                out.writeInt((2<<8)|1);out.writeInt((3<<8)|1);out.writeInt((4<<8)|1);
                out.seek(4096);out.writeInt(-1);out.writeInt(12);out.writeInt(13);
                out.seek(8192);out.writeInt(compressed.length+1);out.writeByte(2);out.write(compressed);
                out.seek(12288);out.writeInt(nbt.length+1);out.writeByte(3);out.write(nbt);
                out.seek(16384);out.writeInt(1);out.writeByte(128|2);
            }
            Files.write(dir.resolve("c.-30.64.mcc"),compressed);
            try(var cursor=new SavedArchiveCursor(dir)){
                var a=cursor.step(1,1);var b=cursor.step(1,1);var c=cursor.step(1,1);
                check(a.candidates().size()==1&&a.regionsOpened()==1&&b.regionsOpened()==0&&c.regionsOpened()==0,"bounded lazy discovery");
                check(cursor.step(1,1).exhausted(),"archive exhausted");
            }
            try(var region=new AnvilRegionReader(path,-1,2)){
                var all=region.discover();check(all.size()==3,"discovery");
                check(all.get(0).x()==-32&&all.get(0).z()==64&&all.get(0).timestamp()==4294967295L,"negative region");
                for(var e:all)check(Arrays.equals(region.load(e).nbt(),nbt),"decode");
                check(region.identity(-29,64)==null,"unknown");
                try(var out=new RandomAccessFile(path.toFile(),"rw")){out.seek(4096);out.writeInt(42);}
                reject(()->region.load(all.get(0)));
                try(var out=new RandomAccessFile(path.toFile(),"rw")){out.seek(4);out.writeInt((2<<8)|1);}
                reject(region::discover);
            }
            // Real open Minecraft files may not pad their final sector until close.
            Path tail=dir.resolve("r.3.4.mca");
            try(var out=new RandomAccessFile(tail.toFile(),"rw")){
                out.setLength(8192);out.seek(0);out.writeInt((2<<8)|1);
                out.seek(8192);out.writeInt(compressed.length+1);out.writeByte(2);out.write(compressed);
            }
            try(var region=new AnvilRegionReader(tail,3,4)){
                var saved=region.discover().getFirst();
                check(Arrays.equals(region.load(saved).nbt(),nbt),"unpadded complete final payload");
                try(var out=new RandomAccessFile(tail.toFile(),"rw")){out.setLength(out.length()-1);}
                reject(region::discover);reject(()->region.load(saved));
                try(var out=new RandomAccessFile(tail.toFile(),"rw")){
                    out.seek(0);out.writeInt((2<<8)|2);
                    out.seek(8192);out.writeInt(compressed.length+1);out.writeByte(2);out.write(compressed);
                }
                reject(region::discover); // an entire allocated sector is missing
                try(var out=new RandomAccessFile(tail.toFile(),"rw")){
                    out.seek(0);out.writeInt((2<<8)|1);out.seek(8192);out.writeInt(4096);
                }
                reject(region::discover); // length exceeds allocation
            }
            Files.delete(tail);
            // A broken region must not prevent traversal of unrelated valid files.
            Files.write(dir.resolve("r.1.1.mca"),new byte[]{1,2,3});
            try(var cursor=new SavedArchiveCursor(dir)){
                int failures=0;boolean done=false;
                for(int i=0;i<10&&!done;i++){
                    var step=cursor.step(2,4);failures+=step.failedRegions().size();done=step.exhausted();
                }
                check(done&&failures>=1,"corrupt regions reported without starvation");
            }
            System.out.println("PASS: Anvil read-only discovery, negative coords, zlib/raw/external NBT, changed identity and overlapping sector rejection");
        }finally{ScaleTest.delete(dir);}
    }
}
