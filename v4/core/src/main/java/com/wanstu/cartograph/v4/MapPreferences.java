package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Small versioned client-only preferences. No world identifiers or coordinates on disk. */
public record MapPreferences(MapRaster.Style style,int layers,int categories,double ui,double labels,double markers,boolean followZoom){
    private static final int MAGIC=0x43563450,VERSION=1,LENGTH=49;
    public MapPreferences{
        Objects.requireNonNull(style);
        if((layers&~MapLayers.ALL_LAYERS)!=0||(categories&~StructureCatalog.ALL_CATEGORIES)!=0||
            !between(ui,1,20)||!between(labels,.5,4)||!between(markers,.5,4))
            throw new IllegalArgumentException("Invalid map preferences");
    }
    private static boolean between(double v,double min,double max){return Double.isFinite(v)&&v>=min&&v<=max;}
    public static MapPreferences capture(MapRaster.Style style,MapLayers layers,MapDisplaySettings display){
        return new MapPreferences(style,layers.mask(),layers.categoryMask(),display.ui(),display.labels(),display.markers(),display.followZoom());
    }
    public static MapPreferences defaults(){return capture(MapRaster.Style.SATELLITE_MODERN,new MapLayers(),new MapDisplaySettings());}
    public void apply(MapLayers layerState,MapDisplaySettings display){
        layerState.restore(layers,categories);display.ui(ui);display.labels(labels);display.markers(markers);display.followZoom(followZoom);
    }
    public static MapPreferences read(Path path)throws IOException{
        if(!Files.exists(path))return defaults();
        byte[] data;
        try(var in=Files.newInputStream(path)){data=in.readNBytes(LENGTH+1);}
        if(data.length!=LENGTH)throw new IOException("Invalid preferences length");
        var crc=new CRC32C();crc.update(data,0,LENGTH-4);
        var b=ByteBuffer.wrap(data);
        if(b.getInt()!=MAGIC||b.getInt()!=VERSION)throw new IOException("Unknown preferences format");
        int style=b.getInt(),layers=b.getInt(),categories=b.getInt();
        double ui=b.getDouble(),labels=b.getDouble(),markers=b.getDouble();
        byte follow=b.get();
        if(b.getInt()!=(int)crc.getValue()||style<0||style>=MapRaster.Style.values().length||follow<0||follow>1)
            throw new IOException("Invalid preferences checksum or value");
        try{return new MapPreferences(MapRaster.Style.values()[style],layers,categories,ui,labels,markers,follow==1);}
        catch(IllegalArgumentException ex){throw new IOException("Invalid preferences",ex);}
    }
    /** Atomic replacement. Failure leaves the previous file intact. */
    public void write(Path path)throws IOException{
        path=path.toAbsolutePath();Files.createDirectories(path.getParent());
        var b=ByteBuffer.allocate(LENGTH);
        b.putInt(MAGIC).putInt(VERSION).putInt(style.ordinal()).putInt(layers).putInt(categories)
            .putDouble(ui).putDouble(labels).putDouble(markers).put((byte)(followZoom?1:0));
        var crc=new CRC32C();crc.update(b.array(),0,LENGTH-4);b.putInt((int)crc.getValue());b.flip();
        Path tmp=Files.createTempFile(path.getParent(),"cartograph-v4-prefs-",".tmp");
        try{
            try(var ch=FileChannel.open(tmp,StandardOpenOption.WRITE)){while(b.hasRemaining())ch.write(b);ch.force(true);}
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
}
