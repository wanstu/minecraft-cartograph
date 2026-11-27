package com.wanstu.cartograph.v4.neoforge;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.wanstu.cartograph.v4.MapIcons;
import com.wanstu.cartograph.v4.PlayerHeading;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** White-alpha padded atlas, one upload per session and one quad per symbol. */
final class V4IconAtlas implements AutoCloseable {
    private ResourceLocation id;
    private static final int EDGE=MapIcons.SIZE,STRIDE=EDGE+4,W=MapIcons.COLUMNS*STRIDE,H=MapIcons.rows()*STRIDE;
    private record Draw(MapIcons.Icon icon,double cx,double cy,int size,int argb,double cos,double sin){}
    private final List<Draw> pending=new ArrayList<>(256);
    private void ensure(){
        if(id!=null)return;
        var image=new NativeImage(W,H,true);
        for(var icon:MapIcons.Icon.values()){
            byte[] alpha=MapIcons.raster(icon);int ox=icon.ordinal()%MapIcons.COLUMNS*STRIDE+2,oy=icon.ordinal()/MapIcons.COLUMNS*STRIDE+2;
            for(int y=0;y<EDGE;y++)for(int x=0;x<EDGE;x++)image.setPixelRGBA(ox+x,oy+y,((alpha[y*EDGE+x]&255)<<24)|0xffffff);
        }
        var texture=new DynamicTexture(image);texture.setFilter(true,false);
        id=Minecraft.getInstance().getTextureManager().register("cartograph_v4_icons",texture);
    }
    void draw(MapIcons.Icon icon,int cx,int cy,int size,int argb){
        if(size>0&&pending.size()<512)pending.add(new Draw(icon,cx,cy,size,argb,1,0));
    }
    void player(double cx,double cy,PlayerHeading.Direction direction){
        if(pending.size()>510)return;
        // The masks point up in texture space. Rotate their tip to the projected heading.
        double cos=-direction.y(),sin=direction.x();
        pending.add(new Draw(MapIcons.Icon.PLAYER_BORDER,cx,cy,PlayerHeading.SIZE,0xff20262d,cos,sin));
        pending.add(new Draw(MapIcons.Icon.PLAYER_FILL,cx,cy,PlayerHeading.SIZE,0xffffd566,cos,sin));
    }
    private static void vertex(BufferBuilder b,org.joml.Matrix4f pose,Draw d,double x,double y,float u,float v){
        b.addVertex(pose,(float)(d.cx+x*d.cos-y*d.sin),(float)(d.cy+x*d.sin+y*d.cos),0)
            .setUv(u,v).setColor(d.argb);
    }
    void flush(GuiGraphics g){
        if(pending.isEmpty())return;
        ensure();g.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0,id);RenderSystem.setShaderColor(1,1,1,1);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableDepthTest();
        try{
            var b=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
            var pose=g.pose().last().pose();
            for(var d:pending){
                int ox=d.icon.ordinal()%MapIcons.COLUMNS*STRIDE+2,oy=d.icon.ordinal()/MapIcons.COLUMNS*STRIDE+2;
                float u=ox/(float)W,v=oy/(float)H,u1=(ox+EDGE)/(float)W,v1=(oy+EDGE)/(float)H;
                double half=d.size*.5;
                vertex(b,pose,d,-half,half,u,v1);
                vertex(b,pose,d,half,half,u1,v1);
                vertex(b,pose,d,half,-half,u1,v);
                vertex(b,pose,d,-half,-half,u,v);
            }
            BufferUploader.drawWithShader(b.buildOrThrow());
        }finally{pending.clear();RenderSystem.enableDepthTest();RenderSystem.disableBlend();}
    }
    @Override public void close(){pending.clear();if(id!=null){Minecraft.getInstance().getTextureManager().release(id);id=null;}}
}
