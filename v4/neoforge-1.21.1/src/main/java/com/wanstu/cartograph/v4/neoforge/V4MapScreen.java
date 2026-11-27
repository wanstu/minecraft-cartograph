package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** One camera for flat and raised maps; angle changes never alter geography. */
final class V4MapScreen extends Screen {
    private final V4MapSession session;
    private MapCamera camera,lastCamera;
    private MapProjection projection;
    private MapViewport.Plan plan;
    private int lastWidth,lastHeight;
    private static final double[] ANGLES={90,60,45,30};
    V4MapScreen(V4MapSession session,double x,double z){
        super(Component.literal("Cartograph V4 Dev"));this.session=session;
        var player=net.minecraft.client.Minecraft.getInstance().player;
        camera=new MapCamera(x,player==null?64:player.getY(),z,1,session.yaw,session.elevation);
    }
    @Override public boolean isPauseScreen(){return false;}
    private static double clamp(double v){return Math.max(-29_999_999,Math.min(29_999_999,v));}
    private double minScale(){return Math.max(3.0/1024,Math.max(width,height)/(1024.0*32*12));}
    private void setCamera(MapCamera c){
        camera=new MapCamera(clamp(c.x()),c.y(),clamp(c.z()),c.pixelsPerBlock(),c.yawDegrees(),c.elevationDegrees());
        session.yaw=camera.yawDegrees();session.elevation=camera.elevationDegrees();
    }
    private void updatePlan(){
        double scale=Math.max(minScale(),Math.min(16,camera.pixelsPerBlock()));
        if(scale!=camera.pixelsPerBlock())setCamera(new MapCamera(camera.x(),camera.y(),camera.z(),scale,camera.yawDegrees(),camera.elevationDegrees()));
        if(plan==null||!camera.equals(lastCamera)||width!=lastWidth||height!=lastHeight){
            projection=new MapProjection(camera);
            double minY=minecraft.level==null?-64:minecraft.level.getMinBuildHeight(),maxY=minecraft.level==null?320:minecraft.level.getMaxBuildHeight();
            plan=projection.plan(width,height,minY,maxY,camera.elevationDegrees()==90?384:64);
            lastCamera=camera;lastWidth=width;lastHeight=height;
        }
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        graphics.fill(0,0,width,height,0xff242b30);
        updatePlan();session.prepare(plan.keys());
        session.draw(graphics,plan.keys(),camera,projection,width,height);
        if(minecraft!=null&&minecraft.player!=null){
            var p=projection.project(minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ());
            int px=(int)Math.round(width/2.0+p.x()),pz=(int)Math.round(height/2.0+p.y());
            if(px>=0&&px<width&&pz>=0&&pz<height){
                graphics.fill(px-4,pz-4,px+5,pz+5,0xff111111);
                graphics.fill(px-2,pz-2,px+3,pz+3,0xffffcc55);
            }
        }
        graphics.fill(0,0,width,52,0xde151a20);
        graphics.drawString(font,"Cartograph V4 | "+session.style.name()+" | LOD "+plan.lod(),8,6,0xffffffff);
        graphics.drawString(font,"Drag / scroll | 1-5 styles | R center | Esc close",8,17,0xffc1cad0);
        graphics.drawString(font,"T tilt "+(int)camera.elevationDegrees()+" | Q/E rotate "+(int)camera.yawDegrees()+" | N north-up",8,28,0xffc1cad0);
        graphics.drawString(font,"X "+(int)camera.x()+" Z "+(int)camera.z()+" | pages "+session.exactPages()+"+"+session.fallbackPages()+"/"+plan.keys().size()+" | quads "+session.quads()+" | uploads "+session.uploads(),8,39,0xff9fadb8);
    }
    @Override public boolean mouseClicked(double mx,double my,int button){if(button==0)return true;return super.mouseClicked(mx,my,button);}
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(button!=0)return super.mouseDragged(mx,my,button,dx,dy);
        var p=camera.atHeight(-dx,-dy,camera.y());
        setCamera(new MapCamera(p.x(),camera.y(),p.z(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));
        return true;
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){
        double next=Math.max(minScale(),Math.min(16,camera.pixelsPerBlock()*Math.pow(1.25,vertical)));
        var anchor=camera.atHeight(mx-width/2.0,my-height/2.0,camera.y());
        setCamera(camera.zoomAt(next,anchor));return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_M){onClose();return true;}
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_5){session.style=MapRaster.Style.values()[key-GLFW.GLFW_KEY_1];return true;}
        if(key==GLFW.GLFW_KEY_R&&minecraft!=null&&minecraft.player!=null){
            setCamera(new MapCamera(minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));return true;
        }
        if(key==GLFW.GLFW_KEY_T){
            int i=0;while(i<ANGLES.length-1&&ANGLES[i]!=camera.elevationDegrees())i++;
            setCamera(camera.withAngles(camera.yawDegrees(),ANGLES[(i+1)%ANGLES.length]));return true;
        }
        if(key==GLFW.GLFW_KEY_Q||key==GLFW.GLFW_KEY_E){setCamera(camera.withAngles(camera.yawDegrees()+(key==GLFW.GLFW_KEY_Q?-45:45),camera.elevationDegrees()));return true;}
        if(key==GLFW.GLFW_KEY_N){setCamera(camera.withAngles(0,90));return true;}
        return super.keyPressed(key,scan,modifiers);
    }
}
