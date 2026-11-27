package com.wanstu.cartograph.v4;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;
public final class CheckpointTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-checkpoint-");
        try{
            var feature=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
            var a=Entry.leaf(feature,75,75,0,100,200,300);
            var b=Entry.leaf(feature,85,85,0,100,200,300);
            Key key=new Key(0,0,0),other=new Key(0,1,0);
            Page p1=new Page(key,1,Map.of(0,a)),p2=new Page(key,2,Map.of(0,b));
            RegionPageStore s=new RegionPageStore(root,4);
            s.writeBatch(List.of(p1));s.checkpoint();
            Path file=root.resolve("pages.cv4log"),idx=root.resolve("pages.cv4idx");
            long length=Files.size(file);
            s=new RegionPageStore(root,4);
            check(s.startupScanBytes()==0&&s.read(key).equals(p1),"checkpoint skips committed prefix");
            s.writeBatch(List.of(p2,new Page(other,1,Map.of(0,a))));
            long tail=Files.size(file)-length;
            s=new RegionPageStore(root,4);
            check(s.startupScanBytes()==tail&&s.read(key).equals(p2)&&s.read(other).revision()==1,"tail replay");
            long full=Files.size(file);
            try(var out=new DataOutputStream(Files.newOutputStream(file,StandardOpenOption.APPEND))){
                out.writeInt(0x4356344c);out.writeInt(4096);out.write(new byte[10]);
            }
            s=new RegionPageStore(root,4);
            check(Files.size(file)==full&&s.read(key).equals(p2),"incomplete tail recovered");
            // A damaged derived index falls back to validating the complete log.
            byte[] bytes=Files.readAllBytes(idx);bytes[3]^=1;Files.write(idx,bytes);
            s=new RegionPageStore(root,4);
            check(s.startupScanBytes()==full&&s.read(key).equals(p2),"bad index fallback");
            s.checkpoint();
            // Indexed read must still reject damaged page content.
            try(var in=new RandomAccessFile(file.toFile(),"rw")){
                long offset=length+36+4;
                in.seek(offset);int v=in.readUnsignedByte();in.seek(offset);in.writeByte(v^1);
            }
            s=new RegionPageStore(root,4);
            try{s.read(key);throw new AssertionError("corrupted indexed page accepted");}
            catch(IOException expected){}
            System.out.println("PASS: checkpoint zero prefix scan, tail replay, interrupted append, damaged-index fallback, indexed page checksum");
        }finally{ScaleTest.delete(root);}
    }
}
