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
