package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Bounded atlas, one terrain draw batch. All GPU operations run on the render thread. */
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
    private List<Key> previousVisible;
    private List<Key> requested=List.of(),uploadOrder=List.of();
    private long uploads;
    private int drawCalls,exactPages,fallbackPages,quads;
    V4MapSession(WorldIoService io,String dimension){
        this.io=io;this.dimension=dimension;
        pages=new MapPresentationClient(io.presentationChanges(dimension),k->io.raster(dimension,k),512,4);
        for(int i=0;i<COUNT;i++)free.add(i);
    }
    private void ensureAtlas(){
        if(texture!=null)return;
        texture=new DynamicTexture(SIZE,SIZE,true);
        texture.setFilter(false,false);
        textureId=Minecraft.getInstance().getTextureManager().register("cartograph_v4_atlas",texture);
    }
    private TextureKey key(MapRaster raster){return new TextureKey(raster.key(),style,raster.generation());}
    private int abgr(int argb){return (argb&0xff00ff00)|((argb&0xff)<<16)|((argb>>>16)&0xff);}
    private MapTileCoverage.State state(Key k){
        var r=pages.get(k);
        if(r==null)return MapTileCoverage.State.MISSING;
        if(r.empty())return MapTileCoverage.State.EMPTY;
        return slots.containsKey(key(r))?MapTileCoverage.State.READY:MapTileCoverage.State.MISSING;
    }
    /** Parent preview first; visible exact tiles next. Neighbor ring is CPU prefetch only. */
    void prepare(List<Key> visible){
        if(visible!=previousVisible){
            previousVisible=visible;
            requested=MapTileCoverage.requests(visible,Math.min(480,visible.size()+64));
            Set<Key> urgent=new HashSet<>(visible);
            for(Key k:visible){Key p=MapTileCoverage.parent(k);if(p!=null)urgent.add(p);}
            uploadOrder=requested.stream().filter(urgent::contains).toList();
        }
        pages.tick(requested);
        Set<TextureKey> protectedKeys=new HashSet<>();
        for(Key k:visible){
            var r=pages.get(k);
            if(r!=null&&!r.empty()){var tk=key(r);protectedKeys.add(tk);slots.get(tk);}
            // Retain existing parent previews unless exact coverage is already uploaded.
            if(state(k)==MapTileCoverage.State.MISSING){
                for(var patch:MapTileCoverage.resolve(k,this::state)){
                    var source=pages.get(patch.source());
                    if(source!=null){var tk=key(source);slots.get(tk);}
                }
            }
        }
        int budget=4;
        long started=System.nanoTime();
        for(Key k:uploadOrder){
            if(budget==0||System.nanoTime()-started>1_000_000)break;
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
    /** One draw call for all visible terrain, including clipped LOD previews. */
    void draw(GuiGraphics graphics,List<Key> visible,double cx,double cz,double scale,int width,int height){
        drawCalls=exactPages=fallbackPages=quads=0;
        if(texture==null)return;
        BufferBuilder buffer=null;
        var pose=graphics.pose().last().pose();
        for(Key k:visible){
            var patches=MapTileCoverage.resolve(k,this::state);
            if(patches.isEmpty())continue;
            if(patches.size()==1&&patches.getFirst().source().equals(k))exactPages++;else fallbackPages++;
            double blocks=32.0*(1<<k.lod());
            double left=width/2.0+(k.x()*blocks-cx)*scale,top=height/2.0+(k.z()*blocks-cz)*scale,size=blocks*scale;
            for(var patch:patches){
                var raster=pages.get(patch.source());if(raster==null)continue;
                Integer slot=slots.get(key(raster));if(slot==null)continue;
                float x0=(float)(left+patch.x()*size),y0=(float)(top+patch.z()*size);
                float x1=(float)(x0+patch.size()*size),y1=(float)(y0+patch.size()*size);
                float u0=(float)((slot%32*EDGE+patch.u()*EDGE)/SIZE),v0=(float)((slot/32*EDGE+patch.v()*EDGE)/SIZE);
                float u1=(float)(u0+patch.uvSize()*EDGE/SIZE),v1=(float)(v0+patch.uvSize()*EDGE/SIZE);
                if(buffer==null){
                    graphics.flush();
                    RenderSystem.setShaderTexture(0,textureId);
                    RenderSystem.setShader(GameRenderer::getPositionTexShader);
                    RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
                    buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX);
                }
                buffer.addVertex(pose,x0,y0,0).setUv(u0,v0);
                buffer.addVertex(pose,x0,y1,0).setUv(u0,v1);
                buffer.addVertex(pose,x1,y1,0).setUv(u1,v1);
                buffer.addVertex(pose,x1,y0,0).setUv(u1,v0);
                quads++;
            }
        }
        if(buffer!=null){
            try{BufferUploader.drawWithShader(buffer.buildOrThrow());drawCalls=1;}
            finally{RenderSystem.disableBlend();}
        }
    }
    int drawCalls(){return drawCalls;}
    int exactPages(){return exactPages;}
    int fallbackPages(){return fallbackPages;}
    int quads(){return quads;}
    long uploads(){return uploads;}
    @Override public void close(){
        if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);
        texture=null;textureId=null;slots.clear();free.clear();
    }
}
