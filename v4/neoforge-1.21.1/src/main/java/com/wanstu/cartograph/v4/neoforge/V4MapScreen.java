package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** First native 2D presentation slice. Camera controls do not mutate geography. */
final class V4MapScreen extends Screen {
    private final V4MapSession session;
    private double x,z,scale=1;
    private MapViewport.Plan plan;
    private int lastWidth,lastHeight;
    private double lastX=Double.NaN,lastZ,lastScale;
    V4MapScreen(V4MapSession session,double x,double z){
        super(Component.literal("Cartograph V4 Dev"));this.session=session;this.x=x;this.z=z;
    }
    @Override public boolean isPauseScreen(){return false;}
    private static double clamp(double value){return Math.max(-29_999_999,Math.min(29_999_999,value));}
    private double minScale(){return Math.max(3.0/1024,Math.max(width,height)/(1024.0*32*16));}
    private void updatePlan(){
        scale=Math.max(minScale(),Math.min(16,scale));
        if(plan==null||x!=lastX||z!=lastZ||scale!=lastScale||width!=lastWidth||height!=lastHeight){
            plan=MapViewport.plan(x,z,scale,width,height,384);
            lastX=x;lastZ=z;lastScale=scale;lastWidth=width;lastHeight=height;
        }
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        graphics.fill(0,0,width,height,0xff242b30);
        updatePlan();session.prepare(plan.keys());
        int drawn=0;
        for(Key k:plan.keys()){
            double blocks=32.0*(1<<k.lod());
            int left=(int)Math.round(width/2.0+(k.x()*blocks-x)*scale);
            int top=(int)Math.round(height/2.0+(k.z()*blocks-z)*scale);
            int right=(int)Math.round(width/2.0+((k.x()+1)*blocks-x)*scale);
            int bottom=(int)Math.round(height/2.0+((k.z()+1)*blocks-z)*scale);
            if(session.draw(graphics,k,left,top,right-left,bottom-top))drawn++;
        }
        if(minecraft!=null&&minecraft.player!=null){
            int px=(int)Math.round(width/2.0+(minecraft.player.getX()-x)*scale);
            int pz=(int)Math.round(height/2.0+(minecraft.player.getZ()-z)*scale);
            if(px>=0&&px<width&&pz>=0&&pz<height){
                graphics.fill(px-4,pz-4,px+5,pz+5,0xff111111);
                graphics.fill(px-2,pz-2,px+3,pz+3,0xffffcc55);
            }
        }
        graphics.fill(0,0,width,42,0xde151a20);
        graphics.drawString(font,"Cartograph V4 | "+session.style.name()+" | LOD "+plan.lod(),8,7,0xffffffff);
        graphics.drawString(font,"Drag / scroll | 1-5 styles | R center | Esc close",8,19,0xffc1cad0);
        graphics.drawString(font,"X "+(int)x+" Z "+(int)z+" | pages "+drawn+"/"+plan.keys().size()+" | uploads "+session.uploads(),8,31,0xff9fadb8);
    }
    @Override public boolean mouseClicked(double mx,double my,int button){if(button==0)return true;return super.mouseClicked(mx,my,button);}
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(button!=0)return super.mouseDragged(mx,my,button,dx,dy);
        x=clamp(x-dx/scale);z=clamp(z-dy/scale);return true;
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){
        double next=Math.max(minScale(),Math.min(16,scale*Math.pow(1.25,vertical)));
        double ax=x+(mx-width/2.0)/scale,az=z+(my-height/2.0)/scale;
        x=clamp(ax-(mx-width/2.0)/next);z=clamp(az-(my-height/2.0)/next);scale=next;return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_M){onClose();return true;}
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_5){session.style=MapRaster.Style.values()[key-GLFW.GLFW_KEY_1];return true;}
        if(key==GLFW.GLFW_KEY_R&&minecraft!=null&&minecraft.player!=null){x=minecraft.player.getX();z=minecraft.player.getZ();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
}
