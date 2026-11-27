package com.wanstu.cartograph.v4.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.wanstu.cartograph.v4.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Shared flat/raised depth-aware overlay. Contour calculation is asynchronous; bounded GPU vertex packing runs on the render thread. */
final class V4ContourLayer implements AutoCloseable {
    private static final int MAX_DRAW_SEGMENTS=8192,MAX_LABELS=16;
    private record Label(double x,double z,int height){}
    private final ContourOverlayCache cache=new ContourOverlayCache();
    private List<ContourOverlayCache.Lines> retained=List.of();
    private List<Label> labels=List.of();
    private VertexBuffer buffer;
    private double originX,originZ;
    private int segments;
    private long uploads;
    private boolean limited,lastRaised;
    void draw(GuiGraphics graphics,Font font,List<ContourOverlayCache.Input> inputs,MapCamera camera,
              MapProjection projection,int width,int height,double depthScale){
        boolean raised=camera.elevationDegrees()!=90;
        var snapshot=cache.tick(inputs);
        List<ContourOverlayCache.Lines> ready=new ArrayList<>();
        int count=0;limited=snapshot.limited();
        for(var lines:snapshot.ready()){
            int size=raised?lines.drapedSize():lines.size();
            if(size==0)continue;
            if(count+size>MAX_DRAW_SEGMENTS){limited=true;continue;}
            count+=size;ready.add(lines);
        }
        ready.sort(Comparator.comparingInt((ContourOverlayCache.Lines l)->l.input().key().lod())
            .thenComparingInt(l->l.input().key().x()).thenComparingInt(l->l.input().key().z()));
        if(raised!=lastRaised||!ready.equals(retained)){
            graphics.flush();rebuild(ready,raised);lastRaised=raised;
        }
        if(buffer==null)return;
        double[] m=MapTransform.coefficients(camera,originX,0,originZ,width,height,depthScale,raised);
        if(raised)m[11]+=.01; // bias only depth, not the projected height
        var affine=new org.joml.Matrix4f().set(
            (float)m[0],(float)m[4],(float)m[8],0,
            (float)m[1],(float)m[5],(float)m[9],0,
            (float)m[2],(float)m[6],(float)m[10],0,
            (float)m[3],(float)m[7],(float)m[11],1);
        var matrix=new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose()).mul(affine);
        graphics.flush();
        RenderSystem.setShaderColor(1,1,1,1);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        if(raised){RenderSystem.enableDepthTest();RenderSystem.depthMask(false);}else RenderSystem.disableDepthTest();
        buffer.bind();
        try{buffer.drawWithShader(matrix,RenderSystem.getProjectionMatrix(),GameRenderer.getPositionColorShader());}
        finally{VertexBuffer.unbind();RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.disableBlend();}
        if(!raised)drawLabels(graphics,font,projection,width,height);
    }
    private void rebuild(List<ContourOverlayCache.Lines> ready,boolean raised){
        if(buffer!=null){buffer.close();buffer=null;}
        retained=List.copyOf(ready);segments=0;labels=List.of();
        if(ready.isEmpty())return;
        Key first=ready.getFirst().input().key();
        originX=first.x()*32.0*(1<<first.lod());originZ=first.z()*32.0*(1<<first.lod());
        var builder=Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES,DefaultVertexFormat.POSITION_COLOR);
        List<Label> candidates=new ArrayList<>();
        for(var lines:ready){
            Key k=lines.input().key();double cell=1<<k.lod(),bx=k.x()*32*cell,bz=k.z()*32*cell;
            int step=ContourLines.interval(k.lod()),best=-1;double longest=0;boolean bestMajor=false;
            int size=raised?lines.drapedSize():lines.size();
            for(int i=0;i<size;i++){
                double x0=raised?lines.drapedValue(i,0):lines.value(i,0),z0=raised?lines.drapedValue(i,1):lines.value(i,1);
                double x1=raised?lines.drapedValue(i,2):lines.value(i,2),z1=raised?lines.drapedValue(i,3):lines.value(i,3);
                float y=raised?lines.drapedValue(i,4):0;
                int h=(int)(raised?lines.drapedValue(i,5):lines.value(i,4));boolean major=Math.floorMod(h,step*5)==0;
                int r=major?255:38,g=major?230:35,b=major?181:28,a=major?225:155;
                builder.addVertex((float)(bx+x0*cell-originX),y,(float)(bz+z0*cell-originZ)).setColor(r,g,b,a);
                builder.addVertex((float)(bx+x1*cell-originX),y,(float)(bz+z1*cell-originZ)).setColor(r,g,b,a);
                segments++;
                double length=Math.hypot(x1-x0,z1-z0);
                if(best<0||(major&&!bestMajor)||(major==bestMajor&&length>longest)){best=i;bestMajor=major;longest=length;}
            }
            if(!raised&&best>=0)candidates.add(new Label(bx+(lines.value(best,0)+lines.value(best,2))*.5*cell,
                bz+(lines.value(best,1)+lines.value(best,3))*.5*cell,(int)lines.value(best,4)));
        }
        labels=List.copyOf(candidates);
        var data=builder.build();
        if(data==null)return;
        var next=new VertexBuffer(VertexBuffer.Usage.STATIC);next.bind();
        try{next.upload(data);buffer=next;uploads++;}
        catch(RuntimeException|Error ex){next.close();throw ex;}
        finally{VertexBuffer.unbind();}
    }
    private void drawLabels(GuiGraphics graphics,Font font,MapProjection p,int width,int height){
        List<int[]> occupied=new ArrayList<>();
        for(Label label:labels){
            if(occupied.size()>=MAX_LABELS)break;
            int x=(int)Math.round(width/2.0+p.screenX(label.x,label.z));
            int y=(int)Math.round(height/2.0+p.screenY(label.x,label.height,label.z));
            String value=Integer.toString(label.height);int w=font.width(value);
            if(x-w/2<4||x+w/2>width-4||y<76||y>height-12)continue;
            boolean overlap=false;
            for(int[] prev:occupied)if(Math.abs(prev[0]-x)<(prev[2]+w)/2+24&&Math.abs(prev[1]-y)<24){overlap=true;break;}
            if(overlap)continue;
            occupied.add(new int[]{x,y,w});
            graphics.fill(x-w/2-2,y-6,x+(w+1)/2+2,y+5,0xb52a2621);
            graphics.drawString(font,value,x-w/2,y-4,0xffffebc7,false);
        }
    }
    int segments(){return segments;}
    long uploads(){return uploads;}
    boolean limited(){return limited;}
    @Override public void close(){
        cache.close();if(buffer!=null){buffer.close();buffer=null;}retained=List.of();labels=List.of();
    }
}
