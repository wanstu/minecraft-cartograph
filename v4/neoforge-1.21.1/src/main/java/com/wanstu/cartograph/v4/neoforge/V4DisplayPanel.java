package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.Locale;

/** Compact display settings, in the same logical coordinate space as the map toolbar. */
final class V4DisplayPanel {
    private final MapDisplaySettings state;
    private boolean open,dragging,captured,positioned;
    private double x=34,y=38;
    private static final int HEADER=22,ROW=24,COUNT=5;
    V4DisplayPanel(MapDisplaySettings state){this.state=state;}
    boolean isOpen(){return open;}
    void toggle(MapHudLayout hud){
        if(open){close();return;}
        open=true;
        if(!positioned)for(var c:hud.controls())if(c.action()==MapHudLayout.Action.SETTINGS){x=c.bounds().x()+c.bounds().w()+4;y=c.bounds().y();positioned=true;break;}
    }
    void close(){open=false;dragging=false;}
    private MapHudLayout.Rect layout(int width,int height){
        int w=Math.min(220,width),h=Math.min(HEADER+COUNT*ROW,height);
        x=Math.max(0,Math.min(x,width-w));y=Math.max(0,Math.min(y,height-h));
        return new MapHudLayout.Rect((int)Math.round(x),(int)Math.round(y),w,h);
    }
    private static String tr(String k,Object... args){return Component.translatable("cartograph_v4.display."+k,args).getString();}
    private static String factor(double v){return String.format(Locale.ROOT,"%.2f×",v);}
    void render(GuiGraphics g,Font font,int width,int height,int mx,int my,double effective){
        if(!open)return;
        var r=layout(width,height);
        g.fill(r.x(),r.y(),r.x()+r.w(),r.y()+r.h(),0xff202932);
        g.renderOutline(r.x(),r.y(),r.w(),r.h(),0xff708795);
        if(r.h()<HEADER||r.w()<120)return;
        g.fill(r.x(),r.y(),r.x()+r.w(),r.y()+HEADER,0xff354653);
        g.drawString(font,tr("title"),r.x()+6,r.y()+7,0xfff0f5f8);
        g.drawCenteredString(font,"×",r.x()+r.w()-12,r.y()+7,0xfff0f5f8);
        String[] names={tr("ui"),tr("labels"),tr("markers"),tr("follow"),tr("reset")};
        String[] values={factor(state.ui()),factor(state.labels()),factor(state.markers()),tr(state.followZoom()?"on":"off"),""};
        for(int i=0;i<COUNT&&HEADER+(i+1)*ROW<=r.h();i++){
            int top=r.y()+HEADER+i*ROW,right=r.x()+r.w();
            if(mx>=r.x()&&mx<right&&my>=top&&my<top+ROW)g.fill(r.x()+1,top,right-1,top+ROW,0xff32424e);
            int labelWidth=Math.max(1,r.w()-(i<3?112:65));
            g.drawString(font,font.plainSubstrByWidth(names[i],labelWidth),r.x()+6,top+4,0xffe4edf3);
            if(i<3){
                g.drawCenteredString(font,values[i],right-54,top+4,0xffb7dce9);
                g.drawCenteredString(font,"−",right-98,top+4,0xffe4edf3);
                g.drawCenteredString(font,"+",right-11,top+4,0xffe4edf3);
                if(i==0&&effective<state.ui())g.drawString(font,tr("fit",factor(effective)),r.x()+6,top+14,0xff8fb2c5);
            }else if(i==3)g.drawCenteredString(font,values[i],right-31,top+6,0xffb7dce9);
        }
    }
    boolean click(double mx,double my,int button,int width,int height){
        if(!open)return false;
        captured=true;var r=layout(width,height);
        if(!r.contains(mx,my)){close();return true;}
        if(button!=0)return true;
        if(my<r.y()+HEADER){if(mx>=r.x()+r.w()-24)close();else dragging=true;return true;}
        if(r.w()<120)return true;
        int row=(int)((my-r.y()-HEADER)/ROW),right=r.x()+r.w();
        if(HEADER+(row+1)*ROW>r.h())return true;
        if(row<3){
            double step=mx>=right-23?.25:mx>=right-110&&mx<right-86?-.25:0;
            if(step!=0)switch(row){case 0->state.ui(state.ui()+step);case 1->state.labels(state.labels()+step);case 2->state.markers(state.markers()+step);}
        }else if(row==3)state.followZoom(!state.followZoom());
        else if(row==4)state.reset();
        return true;
    }
    boolean drag(double dx,double dy,int width,int height){
        if(!open&&!captured)return false;
        if(open&&dragging){x+=dx;y+=dy;layout(width,height);}
        return true;
    }
    boolean release(){boolean consumed=open||captured;captured=false;dragging=false;return consumed;}
}
