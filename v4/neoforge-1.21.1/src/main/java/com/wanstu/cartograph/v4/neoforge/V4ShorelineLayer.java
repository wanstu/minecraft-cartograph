package com.wanstu.cartograph.v4.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.wanstu.cartograph.v4.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import java.util.*;

/** Flat hand-drawn water boundary overlay; geometry uses the exact visible terrain only. */
final class V4ShorelineLayer implements AutoCloseable {
    private List<MapShorelines> retained=List.of();
    private VertexBuffer buffer;
    private double originX,originZ;
    private int segments;
    private long uploads;
    private boolean limited;
    static boolean supported(MapRaster.Style style){
        return style==MapRaster.Style.HAND_MODERN||style==MapRaster.Style.HAND_ANCIENT||style==MapRaster.Style.HAND_ANIME;
    }
    void draw(GuiGraphics graphics,List<MapShorelines> pages,MapRaster.Style style,MapCamera camera,int width,int height){
        if(!pages.equals(retained)){graphics.flush();rebuild(pages);}
        if(buffer==null)return;
        double[] m=MapTransform.coefficients(camera,originX,0,originZ,width,height,1,false);
        var affine=new org.joml.Matrix4f().set(
            (float)m[0],(float)m[4],(float)m[8],0,
            (float)m[1],(float)m[5],(float)m[9],0,
            (float)m[2],(float)m[6],(float)m[10],0,
            (float)m[3],(float)m[7],(float)m[11],1);
        var matrix=new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose()).mul(affine);
        int color=switch(style){
            case HAND_MODERN->0xa849788a;
            case HAND_ANCIENT->0xc06b6654;
            case HAND_ANIME->0xc04780a4;
            default->0;
        };
        graphics.flush();
        RenderSystem.setShaderColor(((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,(color>>>24)/255f);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableDepthTest();
        buffer.bind();
        try{buffer.drawWithShader(matrix,RenderSystem.getProjectionMatrix(),GameRenderer.getPositionColorShader());}
        finally{VertexBuffer.unbind();RenderSystem.setShaderColor(1,1,1,1);RenderSystem.enableDepthTest();RenderSystem.disableBlend();}
    }
    private void rebuild(List<MapShorelines> pages){
        if(buffer!=null){buffer.close();buffer=null;}
        retained=List.copyOf(pages);segments=0;limited=false;
        if(pages.isEmpty())return;
        var plan=MapShorelines.plan(pages,MapShorelines.MAX_SEGMENTS);
        for(var p:plan){segments+=p.runs().size();limited|=p.limited();}
        if(segments==0)return;
        var first=pages.getFirst().key();originX=first.x()*32.0*(1<<first.lod());originZ=first.z()*32.0*(1<<first.lod());
        var builder=Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES,DefaultVertexFormat.POSITION_COLOR);
        for(var p:plan){
            var k=p.key();double cell=1<<k.lod(),bx=k.x()*32*cell-originX,bz=k.z()*32*cell-originZ;
            for(var run:p.runs()){
                builder.addVertex((float)(bx+run.x0()*cell),0,(float)(bz+run.z0()*cell)).setColor(255,255,255,255);
                builder.addVertex((float)(bx+run.x1()*cell),0,(float)(bz+run.z1()*cell)).setColor(255,255,255,255);
            }
        }
        var data=builder.build();
        if(data==null)return;
        var next=new VertexBuffer(VertexBuffer.Usage.STATIC);next.bind();
        try{next.upload(data);buffer=next;uploads++;}
        catch(RuntimeException|Error ex){next.close();throw ex;}
        finally{VertexBuffer.unbind();}
    }
    String status(){return "Shore "+segments+" | VBO "+uploads+(limited?" | limited":"");}
    @Override public void close(){if(buffer!=null){buffer.close();buffer=null;}retained=List.of();segments=0;limited=false;}
}
