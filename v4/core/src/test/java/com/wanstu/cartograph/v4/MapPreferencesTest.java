package com.wanstu.cartograph.v4;

import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class MapPreferencesTest {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static void bad(Path p)throws Exception{
        try{MapPreferences.read(p);throw new AssertionError("Corrupt preferences accepted");}catch(IOException expected){}
    }
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("cartograph-preferences-test"),path=dir.resolve("config").resolve("display.dat");
        try{
            check(MapPreferences.read(path).equals(MapPreferences.defaults()),"missing default");
            int cases=0;
            for(var style:MapRaster.Style.values())for(int mask=0;mask<=MapLayers.ALL_LAYERS;mask++)
                for(int categories:new int[]{0,1,17,128,255}){
                    var p=new MapPreferences(style,mask,categories,12.25,.75,3.5,true);
                    p.write(path);check(p.equals(MapPreferences.read(path)),"durable round trip");
                    var layers=new MapLayers();var display=new MapDisplaySettings();p.apply(layers,display);
                    check(MapPreferences.capture(style,layers,display).equals(p),"complete restoration");
                    cases++;
                }
            byte[] original=Files.readAllBytes(path);
            for(int i=0;i<original.length;i++){
                byte[] corrupt=original.clone();corrupt[i]^=1;Files.write(path,corrupt);bad(path);
            }
            for(int n:new int[]{0,1,15,48,50,5000}){Files.write(path,new byte[n]);bad(path);}
            var defaults=MapPreferences.defaults();defaults.write(path);
            check(defaults.equals(MapPreferences.read(path)),"replace corrupt only on explicit write");
            try(var files=Files.list(path.getParent())){check(files.count()==1,"temporary files removed");}
            var cameras=new DimensionCameras();Object world=new Object(),other=new Object();
            var over=new MapCamera(12,64,-15,4,45,60);
            var nether=new MapCamera(-123,32,456,.2,270,90);
            cameras.world(world);cameras.remember("minecraft:overworld",over);cameras.remember("minecraft:the_nether",nether);
            cameras.world(world);check(over.equals(cameras.recall("minecraft:overworld"))&&nether.equals(cameras.recall("minecraft:the_nether")),"distinct dimension views");
            check(cameras.recall("minecraft:the_end")==null,"new dimension starts fresh");
            for(int i=0;i<100;i++)cameras.remember("test:dimension_"+i,over);
            check(cameras.size()==DimensionCameras.CAPACITY&&cameras.recall("test:dimension_0")==null,"bounded history");
            cameras.world(other);check(cameras.size()==0&&cameras.recall("minecraft:overworld")==null,"new world cannot inherit positions");
            cameras.world(null);cameras.remember("minecraft:overworld",over);check(cameras.size()==0,"no world");
            System.out.println("PASS: "+cases+" preference round trips, corruption/truncation, atomic replacement, bounded dimension history and world isolation");
        }finally{try(var files=Files.walk(dir)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
