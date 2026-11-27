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
    double yaw=0,elevation=90;
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
    /** Same atlas, camera and batch for flat and raised terrain. */
    void draw(GuiGraphics graphics,List<Key> visible,MapCamera camera,MapProjection projection,int width,int height){
        drawCalls=exactPages=fallbackPages=quads=0;
        if(texture==null)return;
        boolean raised=camera.elevationDegrees()!=90;
        graphics.flush();
        RenderSystem.setShaderTexture(0,textureId);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1,1,1,1);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        // Use the GUI depth buffer only for this map pass, not for subsequent HUD elements.
        if(raised){
            RenderSystem.depthMask(true);
            RenderSystem.clear(256,Minecraft.ON_OSX);
            RenderSystem.enableDepthTest();RenderSystem.disableCull();
        }
        var pose=graphics.pose().last().pose();
        BufferBuilder buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
        double depthScale=Math.min(1,70.0/(Math.hypot(width,height)/camera.pixelsPerBlock()*2+8192));
        try{
            for(Key k:visible){
                var patches=MapTileCoverage.resolve(k,this::state);
                if(patches.isEmpty())continue;
                boolean exact=patches.size()==1&&patches.getFirst().source().equals(k);
                if(exact)exactPages++;else fallbackPages++;
                for(var patch:patches){
                    var raster=pages.get(patch.source());if(raster==null)continue;
                    Integer slot=slots.get(key(raster));if(slot==null)continue;
                    double cell=1<<patch.source().lod();
                    double bx=patch.source().x()*32.0*cell,bz=patch.source().z()*32.0*cell;
                    float u=(slot%32*EDGE)/(float)SIZE,v=(slot/32*EDGE)/(float)SIZE;
                    double x0=patch.u()*32,z0=patch.v()*32,x1=x0+patch.uvSize()*32,z1=z0+patch.uvSize()*32;
                    if(!raised){
                        top(buffer,pose,projection,width,height,depthScale,bx,bz,cell,x0,z0,x1,z1,camera.y(),u,v,255,false);
                    }else{
                        var mesh=raster.relief();
                        for(int i=0;i<mesh.topCount();i++){
                            double left=Math.max(x0,mesh.topX(i)),right=Math.min(x1,mesh.topX(i)+mesh.topWidth(i));
                            double near=Math.max(z0,mesh.topZ(i)),far=Math.min(z1,mesh.topZ(i)+mesh.topDepth(i));
                            if(left<right&&near<far)top(buffer,pose,projection,width,height,depthScale,bx,bz,cell,left,near,right,far,mesh.topHeight(i),u,v,255,true);
                        }
                        for(int i=0;i<mesh.wallCount();i++){
                            int x=mesh.wallX(i),z=mesh.wallZ(i),dir=mesh.wallDirection(i);
                            var segment=ReliefMesh.clipWall(x,z,dir,x0,z0,x1,z1);
                            if(segment==null)continue;
                            wall(buffer,pose,projection,width,height,depthScale,bx,bz,cell,x,z,dir,
                                mesh.wallTop(i),mesh.wallBottom(i),u,v,segment.x(),segment.z(),segment.length());
                        }
                        if(exact)boundaryWalls(buffer,pose,projection,width,height,depthScale,raster,bx,bz,cell,u,v);
                    }
                }
            }
            var built=buffer.build();
            if(built!=null){BufferUploader.drawWithShader(built);drawCalls=1;}
        }finally{
            if(raised){
                RenderSystem.clear(256,Minecraft.ON_OSX);
                RenderSystem.enableCull();RenderSystem.enableDepthTest();
            }
            RenderSystem.disableBlend();
        }
    }
    private void vertex(BufferBuilder b,org.joml.Matrix4f pose,MapProjection p,int w,int h,double depthScale,
                        double x,double y,double z,float u,float v,int light,boolean raised){
        float px=(float)(w/2.0+p.screenX(x,z)),py=(float)(h/2.0+p.screenY(x,y,z));
        float depth=raised?(float)(100+p.depth(x,y,z)*depthScale):0;
        b.addVertex(pose,px,py,depth).setUv(u,v).setColor(light,light,light,255);
    }
    private void top(BufferBuilder b,org.joml.Matrix4f pose,MapProjection p,int w,int h,double depthScale,
                     double bx,double bz,double cell,double x0,double z0,double x1,double z1,double y,float u,float v,int light,boolean raised){
        float u0=u+(float)(x0/SIZE),v0=v+(float)(z0/SIZE),u1=u+(float)(x1/SIZE),v1=v+(float)(z1/SIZE);
        vertex(b,pose,p,w,h,depthScale,bx+x0*cell,y,bz+z0*cell,u0,v0,light,raised);
        vertex(b,pose,p,w,h,depthScale,bx+x0*cell,y,bz+z1*cell,u0,v1,light,raised);
        vertex(b,pose,p,w,h,depthScale,bx+x1*cell,y,bz+z1*cell,u1,v1,light,raised);
        vertex(b,pose,p,w,h,depthScale,bx+x1*cell,y,bz+z0*cell,u1,v0,light,raised);
        quads++;
    }
    private void wall(BufferBuilder b,org.joml.Matrix4f pose,MapProjection p,int w,int h,double depthScale,
                      double bx,double bz,double cell,int x,int z,int dir,float top,float bottom,float u,float v,double edgeX,double edgeZ,double length){
        if(top<=bottom)return;
        double ax=bx+edgeX*cell,az=bz+edgeZ*cell;
        double ex=ax+(dir>=2?cell*length:0),ez=az+(dir<2?cell*length:0);
        float tu=u+(x+.5f)/SIZE,tv=v+(z+.5f)/SIZE;
        int light=dir==0||dir==2?172:211;
        vertex(b,pose,p,w,h,depthScale,ax,top,az,tu,tv,light,true);
        vertex(b,pose,p,w,h,depthScale,ax,bottom,az,tu,tv,light,true);
        vertex(b,pose,p,w,h,depthScale,ex,bottom,ez,tu,tv,light,true);
        vertex(b,pose,p,w,h,depthScale,ex,top,ez,tu,tv,light,true);
        quads++;
    }
    private void boundaryWalls(BufferBuilder b,org.joml.Matrix4f pose,MapProjection p,int w,int h,double depthScale,
                               MapRaster raster,double bx,double bz,double cell,float u,float v){
        Key k=raster.key();
        for(int dir=0;dir<4;dir++){
            Key other=new Key(k.lod(),k.x()+(dir==0?-1:dir==1?1:0),k.z()+(dir==2?-1:dir==3?1:0));
            if(state(other)!=MapTileCoverage.State.READY)continue;
            MapRaster adjacent=pages.get(other);
            for(int i=0;i<32;i++){
                int x=dir==0?0:dir==1?31:i,z=dir==2?0:dir==3?31:i;
                int nx=dir==0?31:dir==1?0:i,nz=dir==2?31:dir==3?0:i;
                int at=z*32+x,next=nz*32+nx;
                float top=raster.surface(at),bottom=adjacent.surface(next);
                if(raster.coverage(at)==0||adjacent.coverage(next)==0||!Float.isFinite(top)||!Float.isFinite(bottom)||top<=bottom)continue;
                wall(b,pose,p,w,h,depthScale,bx,bz,cell,x,z,dir,top,bottom,u,v,x+(dir==1?1:0),z+(dir==3?1:0),1);
            }
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
