package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.MapLayers;
import com.wanstu.cartograph.v4.MapLayers.Layer;
import com.wanstu.cartograph.v4.StructureCatalog;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Modal map-local layer popover; input is consumed before map interaction. */
final class V4LayerPanel {
    private static final int ROW=20,HEADER=24,COUNT=14;
    private static final Layer[] LAYERS=Layer.values();
    private static final StructureCatalog.Category[] CATEGORIES=StructureCatalog.Category.values();
    private final MapLayers state;
    private boolean open,dragging,captured;
    private int x=-1,y=38,w,h,scroll,rows;
    V4LayerPanel(MapLayers state){this.state=state;}
    private static String tr(String key){return Component.translatable("cartograph_v4.layers."+key).getString();}
    private void layout(int width,int height){
        w=Math.min(192,Math.max(1,width-8));
        rows=Math.max(0,Math.min(COUNT,(height-HEADER-8)/ROW));
        h=HEADER+rows*ROW;
        if(x<0)x=34;
        x=Math.max(0,Math.min(x,width-w));y=Math.max(0,Math.min(y,height-h));
        scroll=Math.max(0,Math.min(scroll,COUNT-rows));
    }
    void toggle(){open=!open;dragging=false;}
    boolean isOpen(){return open;}
    void close(){open=false;dragging=false;}
    void render(GuiGraphics g,Font font,int width,int height,int mx,int my){
        if(!open)return;
        layout(width,height);
        g.fill(x,y,x+w,y+h,0xff202932);
        g.renderOutline(x,y,w,h,0xff708795);
        g.fill(x+1,y+1,x+w-1,y+HEADER,0xff354653);
        g.drawString(font,font.plainSubstrByWidth(tr("title"),Math.max(1,w-30)),x+7,y+8,0xfff0f5f8);
        g.drawCenteredString(font,"×",x+w-13,y+8,0xfff0f5f8);
        for(int i=0;i<rows;i++){
            int row=scroll+i,top=y+HEADER+i*ROW;
            boolean hovered=mx>=x&&mx<x+w&&my>=top&&my<top+ROW;
            if(hovered)g.fill(x+1,top,x+w-1,top+ROW,0xff32424e);
            String label;boolean selected;
            if(row<4){label=tr(LAYERS[row].name().toLowerCase(java.util.Locale.ROOT));selected=state.visible(LAYERS[row]);}
            else if(row<12){var category=CATEGORIES[row-4];label=tr("category."+category.name().toLowerCase(java.util.Locale.ROOT));selected=state.categoryVisible(category);}
            else {label=tr(row==12?"all":"none");selected=false;}
            int left=x+(row>=4&&row<12?16:7);
            if(row<12){
                g.renderOutline(left,top+5,10,10,0xff8296a4);
                if(selected)g.fill(left+2,top+7,left+8,top+13,0xff86c7e6);
                left+=15;
            }
            g.drawString(font,font.plainSubstrByWidth(label,Math.max(1,x+w-left-8)),left,top+6,0xffdae6ee);
        }
        if(rows>0&&rows<COUNT){
            int top=y+HEADER,track=rows*ROW,thumb=Math.max(5,track*rows/COUNT);
            int pos=top+(track-thumb)*scroll/(COUNT-rows);
            g.fill(x+w-4,pos,x+w-1,pos+thumb,0xff7794a6);
        }
    }
    boolean click(double mx,double my,int button,int width,int height){
        if(!open)return false;
        captured=true;
        layout(width,height);
        if(mx<x||mx>=x+w||my<y||my>=y+h){close();return true;}
        if(button!=0)return true;
        if(my<y+HEADER){
            if(mx>=x+w-25)close();else dragging=true;
            return true;
        }
        int row=scroll+(int)(my-y-HEADER)/ROW;
        if(row<4)state.toggle(LAYERS[row]);
        else if(row<12)state.toggleCategory(CATEGORIES[row-4]);
        else if(row<COUNT)state.setAllCategories(row==12);
        return true;
    }
    boolean drag(double dx,double dy,int width,int height){
        if(!open&&!captured)return false;
        if(open&&dragging){x=(int)Math.round(x+dx);y=(int)Math.round(y+dy);layout(width,height);}
        return true;
    }
    boolean release(){boolean consumed=open||captured;captured=false;dragging=false;return consumed;}
    boolean scroll(double vertical,int width,int height){
        if(!open)return false;
        layout(width,height);
        if(vertical!=0)scroll=Math.max(0,Math.min(COUNT-rows,scroll+(vertical>0?-1:1)));
        return true;
    }
}
