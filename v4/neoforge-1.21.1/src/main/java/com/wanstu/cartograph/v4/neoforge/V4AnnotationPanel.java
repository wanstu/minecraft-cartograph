package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.MapContextLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Modal editor with a draggable header and whole-row scrolling. Widgets are never recreated during a drag. */
abstract class V4AnnotationPanel extends Screen {
    final V4MapScreen parent;
    private record Item(AbstractWidget widget,int row,int col,int cols,String label){}
    private final List<Item> items=new ArrayList<>();
    private final int rowCount;
    private double px=Double.NaN,py=Double.NaN;
    private int first;
    private boolean dragging;
    private MapContextLayout.Editor layout;
    private boolean lastWaiting;
    V4AnnotationPanel(V4MapScreen parent,Component title,int rowCount){super(title);this.parent=parent;this.rowCount=rowCount;}
    static Component label(String key){return Component.translatable("cartograph_v4.editor."+key);}
    abstract boolean waiting();
    abstract String status();
    abstract boolean hasError();
    abstract void buildFields();
    @Override protected final void init(){
        dragging=false;items.clear();buildFields();
        if(Double.isNaN(px)){px=(width-Math.min(316,width))/2.;py=(height-Math.min(height,23+rowCount*32+40))/2.;}
        place();
    }
    final EditBox field(int row,int col,int cols,String key,int max,String value){
        var f=new EditBox(font,0,0,1,20,label(key));f.setMaxLength(max);f.setValue(value);
        items.add(new Item(addRenderableWidget(f),row,col,cols,key));return f;
    }
    final Button button(int row,int col,int cols,Component name,Button.OnPress press){
        var b=addRenderableWidget(Button.builder(name,press).bounds(0,0,1,20).build());
        items.add(new Item(b,row,col,cols,null));return b;
    }
    private void place(){
        lastWaiting=waiting();
        layout=MapContextLayout.editor(width,height,(int)Math.round(px),(int)Math.round(py),rowCount,first);
        var r=layout.bounds();px=Math.max(0,Math.min(px,width-r.w()));py=Math.max(0,Math.min(py,height-r.h()));first=layout.first();
        for(var item:items){
            boolean visible=r.w()>=32&&(item.row()<0?layout.footer()>=22:item.row()>=first&&item.row()<first+layout.rows());
            var widget=item.widget();widget.visible=visible;widget.active=visible&&!waiting();
            if(widget instanceof EditBox edit)edit.setEditable(visible&&!waiting());
            if(!visible){if(getFocused()==widget)setFocused(null);continue;}
            int space=Math.max(1,r.w()-8),left=r.x()+4+item.col()*space/item.cols(),right=r.x()+4+(item.col()+1)*space/item.cols();
            widget.setX(left);widget.setWidth(Math.max(1,right-left-3));
            widget.setY(item.row()<0?r.y()+r.h()-layout.footer()+1:layout.row(item.row()-first).y()+11);
        }
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(!waiting())minecraft.setScreen(parent);}
    @Override public boolean mouseClicked(double mx,double my,int button){
        if(waiting())return true;
        if(button==0&&layout.bounds().contains(mx,my)&&my<layout.bounds().y()+layout.header()){
            dragging=true;return true;
        }
        super.mouseClicked(mx,my,button);return true;
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(waiting())return true;
        if(dragging&&button==0){px+=dx;py+=dy;place();return true;}
        super.mouseDragged(mx,my,button,dx,dy);return true;
    }
    @Override public boolean mouseReleased(double mx,double my,int button){if(button==0)dragging=false;super.mouseReleased(mx,my,button);return true;}
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){
        if(!waiting()&&vertical!=0){first+=vertical>0?-1:1;place();}return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(waiting())return true;return super.keyPressed(key,scan,modifiers);}
    @Override public boolean charTyped(char ch,int modifiers){return waiting()||super.charTyped(ch,modifiers);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        if(parent.width==width&&parent.height==height){parent.render(g,-10000,-10000,partial);g.flush();}
        else g.fill(0,0,width,height,0xff20262d);
        g.fill(0,0,width,height,0x99111920);if(lastWaiting!=waiting())place();
        var r=layout.bounds();
        g.fill(r.x(),r.y(),r.x()+r.w(),r.y()+r.h(),0xff202932);
        g.renderOutline(r.x(),r.y(),r.w(),r.h(),0xff708795);
        g.fill(r.x(),r.y(),r.x()+r.w(),r.y()+layout.header(),0xff354653);
        if(layout.header()>=15&&r.w()>=32)g.drawString(font,font.plainSubstrByWidth(title.getString(),Math.max(1,r.w()-12)),r.x()+6,r.y()+7,0xffeef4f7);
        for(var item:items)if(item.label()!=null&&item.widget().visible)
            g.drawString(font,font.plainSubstrByWidth(label(item.label()).getString(),item.widget().getWidth()),item.widget().getX(),item.widget().getY()-10,0xffc1cad0);
        super.render(g,mx,my,partial);
        if(layout.footer()>=38){
            String msg=status();
            if(layout.rows()<rowCount&&!waiting())msg=msg.isEmpty()?label("scroll").getString():msg+" · "+label("scroll").getString();
            g.drawString(font,font.plainSubstrByWidth(msg,Math.max(1,r.w()-8)),r.x()+4,r.y()+r.h()-13,hasError()?0xffff9999:0xffb7cbd6);
        }
    }
}
