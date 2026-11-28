package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.MapDisplaySettings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Local scale for native annotation widgets; Minecraft's global GUI scale stays untouched. */
abstract class V4ScaledScreen extends Screen {
    private final V4MapScreen map;
    protected MapDisplaySettings.Ui ui;
    V4ScaledScreen(V4MapScreen map,Component title){super(title);this.map=map;}
    @Override protected final void init(){ui=MapDisplaySettings.Ui.of(map.displaySettings(),width,height);initUi();}
    protected abstract void initUi();
    protected void backdrop(GuiGraphics g,float partial){g.fill(0,0,width,height,0xff20262d);}
    // Screen.render also renders its vanilla background (including blur). Our backdrop
    // has already been drawn; a second background would obscure custom editor labels.
    protected void renderUi(GuiGraphics g,int mx,int my,float partial){for(var widget:renderables)widget.render(g,mx,my,partial);}
    @Override public final void render(GuiGraphics g,int mx,int my,float partial){
        backdrop(g,partial);g.flush();g.pose().pushPose();
        try{
            g.pose().translate(0,0,300);
            g.pose().scale((float)ui.scale(),(float)ui.scale(),1);
            renderUi(g,(int)Math.floor(ui.logical(mx)),(int)Math.floor(ui.logical(my)),partial);g.flush();
        }finally{g.pose().popPose();}
    }
    @Override public final boolean mouseClicked(double x,double y,int b){return clickUi(ui.logical(x),ui.logical(y),b);}
    @Override public final boolean mouseDragged(double x,double y,int b,double dx,double dy){return dragUi(ui.logical(x),ui.logical(y),b,ui.logical(dx),ui.logical(dy));}
    @Override public final boolean mouseReleased(double x,double y,int b){return releaseUi(ui.logical(x),ui.logical(y),b);}
    @Override public final boolean mouseScrolled(double x,double y,double horizontal,double vertical){return scrollUi(ui.logical(x),ui.logical(y),horizontal,vertical);}
    @Override public void mouseMoved(double x,double y){super.mouseMoved(ui.logical(x),ui.logical(y));}
    protected boolean clickUi(double x,double y,int b){return super.mouseClicked(x,y,b);}
    protected boolean dragUi(double x,double y,int b,double dx,double dy){return super.mouseDragged(x,y,b,dx,dy);}
    protected boolean releaseUi(double x,double y,int b){return super.mouseReleased(x,y,b);}
    protected boolean scrollUi(double x,double y,double h,double v){return super.mouseScrolled(x,y,h,v);}
}
