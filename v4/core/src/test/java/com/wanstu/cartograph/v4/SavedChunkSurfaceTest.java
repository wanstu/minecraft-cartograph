package com.wanstu.cartograph.v4;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class SavedChunkSurfaceTest{
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    static void tag(DataOutputStream out,int type,String name)throws IOException{out.writeByte(type);out.writeUTF(name);}
    static void str(DataOutputStream out,String name,String value)throws IOException{tag(out,8,name);out.writeUTF(value);}
    static void integer(DataOutputStream out,String name,int value)throws IOException{tag(out,3,name);out.writeInt(value);}
    static long[] pack(int width,int size,java.util.function.IntUnaryOperator values){
        int per=64/width;long[] words=new long[(size+per-1)/per];
        for(int i=0;i<size;i++)words[i/per]|=(long)values.applyAsInt(i)<<((i%per)*width);
        return words;
    }
    static void longs(DataOutputStream out,String name,long[] values)throws IOException{
        tag(out,12,name);out.writeInt(values.length);for(long v:values)out.writeLong(v);
    }
    static void end(DataOutputStream out)throws IOException{out.writeByte(0);}
    static byte[] fixture()throws IOException{return fixture("minecraft:full");}
    static byte[] fixture(String status)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            tag(out,10,"");
            integer(out,"DataVersion",3955);integer(out,"xPos",-2);integer(out,"zPos",3);str(out,"Status",status);
            // Unknown fields and nested structures must be safely skipped.
            tag(out,10,"Ignored");tag(out,9,"list");out.writeByte(10);out.writeInt(1);str(out,"dummy","test");end(out);end(out);
            tag(out,10,"Heightmaps");
            longs(out,"WORLD_SURFACE",pack(5,256,i->i==255?0:4));
            longs(out,"OCEAN_FLOOR",pack(5,256,i->i==255?0:2));
            longs(out,"MOTION_BLOCKING_NO_LEAVES",pack(5,256,i->i==255?0:3));
            end(out);
            tag(out,9,"sections");out.writeByte(10);out.writeInt(1);
            tag(out,1,"Y");out.writeByte(-1);
            tag(out,10,"block_states");
            tag(out,9,"palette");out.writeByte(10);out.writeInt(2);
            str(out,"Name","minecraft:grass_block");
            tag(out,10,"Properties");str(out,"snowy","false");end(out);end(out);
            str(out,"Name","test:ordered");
            tag(out,10,"Properties");str(out,"z","2");str(out,"a","1");end(out);end(out);
            longs(out,"data",pack(4,4096,i->i&1));end(out);
            tag(out,10,"biomes");tag(out,9,"palette");out.writeByte(8);out.writeInt(2);out.writeUTF("minecraft:plains");out.writeUTF("minecraft:river");
            longs(out,"data",pack(1,64,i->i&1));end(out);end(out);end(out);
        }
        return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception{
        byte[] bytes=fixture();
        var s=SavedChunkSurface.decode(bytes,-2,3,-16,16);
        var c=s.columns();
        check(c[0].material().equals("minecraft:grass_block[snowy=false]")&&c[0].biome().equals("minecraft:plains"),"first palette");
        check(c[1].material().equals("test:ordered[a=1,z=2]")&&c[4].biome().equals("minecraft:river"),"state properties and biome indices");
        check(c[0].surfaceY()==-13&&c[0].reliefY()==-14&&c[0].oceanFloorY()==-15&&c[255]==null,"heightmaps and unknown");
        c[0]=null;check(s.columns()[0]!=null,"immutable snapshot");
        try{SavedChunkSurface.decode(bytes,-1,3,-16,16);throw new AssertionError("wrong coords");}catch(IOException expected){}
        try{SavedChunkSurface.decode(Arrays.copyOf(bytes,bytes.length-1),-2,3,-16,16);throw new AssertionError("truncated");}catch(IOException expected){}
        try{SavedChunkSurface.decode(fixture("minecraft:noise"),-2,3,-16,16);throw new AssertionError("protochunk");}catch(SavedChunkSurface.NotFullyGeneratedException expected){}
        System.out.println("PASS: actual NBT schema, packed height/state/biome indices, canonical properties, unknown columns, bounds and malformed rejection");
    }
}
