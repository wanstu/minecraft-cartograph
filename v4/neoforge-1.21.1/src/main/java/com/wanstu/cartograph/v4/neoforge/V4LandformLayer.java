package com.wanstu.cartograph.v4.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.wanstu.cartograph.v4.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import java.util.*;

/** Bounded retained flat cartographic symbols, derived exclusively from drawn exact pages. */
final class V4LandformLayer implements AutoCloseable {
    private List<MapLandforms> retained=List.of();
    private MapRaster.Style style;
    private VertexBuffer buffer;
    private double originX,originZ;
    private int symbols;
    private long uploads;
    void draw(GuiGraphics g,List<MapLandforms> pages,MapRaster.Style style,MapCamera camera,int width,int height){
        if(pages.isEmpty()||camera.pixelsPerBlock()*(1<<pages.getFirst().key().lod())<2)return;
        if(this.style!=style||!pages.equals(retained)){
            g.flush();rebuild(pages,style);
        }
        if(buffer==null)return;
        double[] m=MapTransform.coefficients(camera,originX,0,originZ,width,height,1,false);
        var affine=new org.joml.Matrix4f().set(
            (float)m[0],(float)m[4],(float)m[8],0,
            (float)m[1],(float)m[5],(float)m[9],0,
            (float)m[2],(float)m[6],(float)m[10],0,
            (float)m[3],(float)m[7],(float)m[11],1);
        var matrix=new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(g.pose().last().pose()).mul(affine);
        g.flush();RenderSystem.setShaderColor(1,1,1,1);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableDepthTest();
        buffer.bind();
        try{buffer.drawWithShader(matrix,RenderSystem.getProjectionMatrix(),GameRenderer.getPositionColorShader());}
        finally{VertexBuffer.unbind();RenderSystem.enableDepthTest();RenderSystem.disableBlend();}
    }
    private void rebuild(List<MapLandforms> pages,MapRaster.Style style){
        if(buffer!=null){buffer.close();buffer=null;}
        retained=List.copyOf(pages);this.style=style;
        var plan=MapLandforms.plan(pages,MapLandforms.MAX_DRAW_SYMBOLS);symbols=plan.size();
        if(plan.isEmpty())return;
        var first=pages.getFirst().key();originX=first.x()*32.0*(1<<first.lod());originZ=first.z()*32.0*(1<<first.lod());
        var builder=Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES,DefaultVertexFormat.POSITION_COLOR);
        for(var placed:plan){
            var k=placed.key();double cell=1<<k.lod(),bx=k.x()*32*cell-originX,bz=k.z()*32*cell-originZ;
            MapLandformGlyphs.emit(placed.symbol(),style,(x0,z0,x1,z1,color)->{
                builder.addVertex((float)(bx+x0*cell),0,(float)(bz+z0*cell)).setColor(color);
                builder.addVertex((float)(bx+x1*cell),0,(float)(bz+z1*cell)).setColor(color);
            });
        }
        var data=builder.build();if(data==null)return;
        var next=new VertexBuffer(VertexBuffer.Usage.STATIC);next.bind();
        try{next.upload(data);buffer=next;uploads++;}
        catch(RuntimeException|Error ex){next.close();throw ex;}
        finally{VertexBuffer.unbind();}
    }
    String status(){return "Relief symbols "+symbols+" | VBO "+uploads;}
    @Override public void close(){if(buffer!=null){buffer.close();buffer=null;}retained=List.of();symbols=0;style=null;}
}
