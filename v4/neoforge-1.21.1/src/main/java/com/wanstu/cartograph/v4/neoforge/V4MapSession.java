package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** One bounded texture atlas per local world/dimension session. All GPU access on client thread. */
final class V4MapSession implements AutoCloseable {
    final WorldIoService io;
    final String dimension;
    final MapPresentationClient pages;
    MapRaster.Style style=MapRaster.Style.SATELLITE_MODERN;
    private static final int SIZE=1024,EDGE=32,COUNT=512;
    private record TextureKey(Key key,MapRaster.Style style,long generation){}
    private final LinkedHashMap<TextureKey,Integer> slots=new LinkedHashMap<>(16,.75f,true);
    private final ArrayDeque<Integer> free=new ArrayDeque<>();
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private long uploads;
    V4MapSession(WorldIoService io,String dimension){
        this.io=io;this.dimension=dimension;
        pages=new MapPresentationClient(io.presentationChanges(dimension),k->io.raster(dimension,k),512,4);
        for(int i=0;i<COUNT;i++)free.add(i);
    }
    private void ensureAtlas(){
        if(texture!=null)return;
        texture=new DynamicTexture(SIZE,SIZE,true);
        textureId=Minecraft.getInstance().getTextureManager().register("cartograph_v4_atlas",texture);
    }
    private TextureKey key(MapRaster raster){return new TextureKey(raster.key(),style,raster.generation());}
    private int abgr(int argb){return (argb&0xff00ff00)|((argb&0xff)<<16)|((argb>>>16)&0xff);}
    /** Upload at most four missing tiles; cached tiles require no upload. */
    void prepare(List<Key> visible){
        pages.tick(visible);
        Set<TextureKey> protectedKeys=new HashSet<>();
        for(Key k:visible){var r=pages.get(k);if(r!=null&&!r.empty()){var key=key(r);protectedKeys.add(key);slots.get(key);}}
        int budget=4;
        for(Key k:visible){
            if(budget==0)break;
            var raster=pages.get(k);if(raster==null||raster.empty())continue;
            var key=key(raster);if(slots.containsKey(key))continue;
            ensureAtlas();
            Integer slot=free.pollFirst();
            if(slot==null){
                var it=slots.entrySet().iterator();
                while(it.hasNext()){
                    var e=it.next();
                    if(!protectedKeys.contains(e.getKey())){slot=e.getValue();it.remove();break;}
                }
            }
            if(slot==null)break;
            int ox=slot%32*EDGE,oy=slot/32*EDGE;
            var image=texture.getPixels();
            for(int i=0;i<1024;i++)image.setPixelRGBA(ox+i%EDGE,oy+i/EDGE,abgr(raster.argb(style,i)));
            texture.bind();image.upload(0,ox,oy,ox,oy,EDGE,EDGE,false,false);
            slots.put(key,slot);budget--;uploads++;
        }
    }
    boolean draw(GuiGraphics graphics,Key k,int x,int y,int w,int h){
        var raster=pages.get(k);if(raster==null||raster.empty()||texture==null)return false;
        Integer slot=slots.get(key(raster));if(slot==null)return false;
        graphics.blit(textureId,x,y,w,h,slot%32*32f,slot/32*32f,32,32,SIZE,SIZE);
        return true;
    }
    long uploads(){return uploads;}
    @Override public void close(){
        if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);
        texture=null;textureId=null;slots.clear();free.clear();
    }
}
