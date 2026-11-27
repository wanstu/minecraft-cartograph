package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import com.wanstu.cartograph.v4.MapViewOptions.Choice;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** View selection popover. No geographic or GPU state ownership. */
final class V4ViewPanel {
    private boolean open,dragging,captured,positioned;
    private static final Choice[] CHOICES=Choice.values();
    private double x=34,y=65;
    private int first;
    private MapViewOptions.MenuLayout layout(int width,int height){
        var layout=MapViewOptions.menu(width,height,(int)Math.round(x),(int)Math.round(y),first);
        // Keep subpixel drag movement until a boundary is reached.
        x=Math.max(0,Math.min(x,width-layout.bounds().w()));
        y=Math.max(0,Math.min(y,height-layout.bounds().h()));
        first=layout.first();return layout;
    }
    boolean isOpen(){return open;}
    void toggle(MapHudLayout hud){
        if(open){close();return;}
        open=true;dragging=false;
        if(!positioned)for(var c:hud.controls())if(c.action()==MapHudLayout.Action.VIEW){
            x=c.bounds().x()+c.bounds().w()+4;y=c.bounds().y();positioned=true;break;
        }
    }
    void close(){open=false;dragging=false;}
    private static String tr(String key){return Component.translatable("cartograph_v4.view."+key).getString();}
    private static String label(Choice choice){
        return choice.style()!=null?Component.translatable("cartograph_v4.hud.style."+choice.style().name().toLowerCase(Locale.ROOT)).getString():tr(choice.name().toLowerCase(Locale.ROOT));
    }
    void render(GuiGraphics g,Font font,int width,int height,int mx,int my,MapRaster.Style style,MapCamera camera,boolean regionSelection){
        if(!open)return;
        var l=layout(width,height);var r=l.bounds();
        g.fill(r.x(),r.y(),r.x()+r.w(),r.y()+r.h(),0xff202932);
        g.renderOutline(r.x(),r.y(),r.w(),r.h(),0xff708795);
        g.fill(r.x(),r.y(),r.x()+r.w(),r.y()+l.header(),0xff354653);
        if(l.header()>=12&&r.w()>=30){
            g.drawString(font,font.plainSubstrByWidth(tr("title"),Math.max(1,r.w()-30)),r.x()+6,r.y()+7,0xfff0f5f8);
            g.drawCenteredString(font,"×",r.x()+r.w()-11,r.y()+7,0xfff0f5f8);
        }
        for(int i=0;i<l.rows();i++){
            var row=l.row(i);var choice=CHOICES[l.first()+i];
            if(row.w()<24)continue;
            boolean enabled=choice.enabled(regionSelection),selected=choice.selected(style,camera);
            if(selected||row.contains(mx,my)&&enabled)g.fill(row.x()+1,row.y(),row.x()+row.w()-1,row.y()+row.h(),selected?0xff3d5767:0xff32424e);
            if(choice==Choice.FLAT||choice==Choice.ROTATE_LEFT)g.fill(row.x()+2,row.y(),row.x()+row.w()-2,row.y()+1,0xff627786);
            if(selected)g.fill(row.x()+5,row.y()+5,row.x()+8,row.y()+13,0xff9cdaef);
            g.drawString(font,font.plainSubstrByWidth(label(choice),Math.max(1,row.w()-24)),row.x()+13,row.y()+5,enabled?0xffe0eaf0:0xff788892);
        }
        int count=CHOICES.length;
        if(l.rows()>0&&l.rows()<count&&r.w()>=8){
            int track=l.rows()*18,thumb=Math.max(4,track*l.rows()/count);
            int pos=r.y()+l.header()+(track-thumb)*first/(count-l.rows());
            g.fill(r.x()+r.w()-4,pos,r.x()+r.w()-1,pos+thumb,0xff7794a6);
        }
    }
    boolean click(double mx,double my,int button,int width,int height,boolean regionSelection,Consumer<Choice> apply){
        if(!open)return false;
        captured=true;
        var l=layout(width,height);var r=l.bounds();
        if(!r.contains(mx,my)){close();return true;}
        if(button!=0)return true;
        if(my<r.y()+l.header()){
            if(mx>=r.x()+r.w()-22)close();else dragging=true;
            return true;
        }
        var choice=l.choiceAt(mx,my);
        if(choice!=null&&choice.enabled(regionSelection))apply.accept(choice);
        return true;
    }
    boolean drag(double dx,double dy,int width,int height){
        if(!open&&!captured)return false;
        if(open&&dragging){positioned=true;x+=dx;y+=dy;layout(width,height);}
        return true;
    }
    boolean release(){boolean consumed=open||captured;captured=false;dragging=false;return consumed;}
    boolean scroll(double vertical,int width,int height){
        if(!open)return false;
        if(vertical!=0){first+=vertical>0?-1:1;layout(width,height);}
        return true;
    }
}
