package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import com.wanstu.cartograph.v4.MarkerStore.Marker;
import com.wanstu.cartograph.v4.MapContextLayout.Action;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** Frozen right-click target; no world reads while open. */
final class V4ContextMenu {
    record Target(Marker marker,MapRayPicker.Hit hit,int x,int z){}
    private Target target;
    private List<Action> actions=List.of();
    private int x,y,first;
    private boolean captured;
    boolean isOpen(){return target!=null;}
    void close(){target=null;actions=List.of();}
    void open(int x,int y,Target target,boolean markersReady,boolean regionsReady){
        close();this.x=x;this.y=y;first=0;
        actions=MapContextLayout.actions(target.marker()!=null,target.hit()!=null,markersReady,regionsReady);
        if(!actions.isEmpty())this.target=target;
    }
    private MapContextLayout.Menu layout(int width,int height){
        var l=MapContextLayout.menu(width,height,x,y,actions.size(),first);first=l.first();return l;
    }
    private static String label(Action a){return Component.translatable("cartograph_v4.context."+a.name().toLowerCase(Locale.ROOT)).getString();}
    void render(GuiGraphics g,Font font,int width,int height,int mx,int my,V4IconAtlas icons){
        if(!isOpen())return;
        var l=layout(width,height);var r=l.bounds();
        if(l.rows()==0)return;
        g.fill(r.x(),r.y(),r.x()+r.w(),r.y()+r.h(),0xff202932);
        for(int i=0;i<l.rows();i++){
            var row=l.row(i);
            if(row.contains(mx,my))g.fill(row.x()+1,row.y(),row.x()+row.w()-1,row.y()+row.h(),0xff3d5767);
            if(row.w()>=32){
                var action=actions.get(l.first()+i);
                icons.draw(MapIcons.context(action),row.x()+10,row.y()+10,12,0xffe4edf3);
                g.drawString(font,font.plainSubstrByWidth(label(action),Math.max(1,row.w()-24)),row.x()+21,row.y()+6,0xffe4edf3);
            }
        }
        g.renderOutline(r.x(),r.y(),r.w(),r.h(),0xff708795);
    }
    record Invocation(Action action,Target target){}
    boolean click(double mx,double my,int button,int width,int height,Consumer<Invocation> apply){
        if(!isOpen())return false;
        captured=true;
        var l=layout(width,height);int index=l.indexAt(mx,my);
        var current=target;Action action=index>=0&&index<actions.size()?actions.get(index):null;
        close();
        if(button==0&&action!=null)apply.accept(new Invocation(action,current));
        return true;
    }
    boolean drag(){return isOpen()||captured;}
    boolean release(){boolean consumed=isOpen()||captured;captured=false;return consumed;}
    boolean scroll(double amount,int width,int height){
        if(!isOpen())return false;
        if(amount!=0){first+=amount>0?-1:1;layout(width,height);}
        return true;
    }
}
