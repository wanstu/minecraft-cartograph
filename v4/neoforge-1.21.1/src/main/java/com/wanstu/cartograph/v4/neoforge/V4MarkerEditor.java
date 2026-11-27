package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.MarkerStore.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Dedicated modal screen so input never passes through to map dragging/shortcuts. */
final class V4MarkerEditor extends Screen {
    final V4MapScreen parent;
    final V4MapSession session;
    private final Marker original;
    private final UUID id;
    private final int initialX,initialY,initialZ;
    private final boolean approximate;
    private EditBox name,description,x,y,z;
    private Icon icon;
    private int color;
    private static final int[] COLORS={0xffcc55,0xffffff,0x72b9e8,0x76c78a,0xec7373,0xc1a1ed};
    private boolean waiting;
    private boolean deleteConfirmation;
    private long receipt;
    private String error;
    V4MarkerEditor(V4MapScreen parent,V4MapSession session,Marker original,int x,int y,int z,boolean approximate){
        super(Component.literal(original==null?"New marker":"Edit marker"));this.parent=parent;this.session=session;this.original=original;
        id=original==null?UUID.randomUUID():original.id();
        initialX=x;initialY=y;initialZ=z;this.approximate=approximate;
        icon=original==null?Icon.POINT:original.icon();color=original==null?COLORS[0]:original.color();
    }
    private EditBox field(int x,int y,int w,int max,String value){
        var f=new EditBox(font,x,y,w,20,Component.literal("Marker field"));f.setMaxLength(max);f.setValue(value);return addRenderableWidget(f);
    }
    @Override protected void init(){
        String n=name==null?(original==null?"":original.name()):name.getValue();
        String d=description==null?(original==null?"":original.description()):description.getValue();
        String xx=x==null?Integer.toString(initialX):x.getValue(),yy=y==null?Integer.toString(initialY):y.getValue(),zz=z==null?Integer.toString(initialZ):z.getValue();
        int left=width/2-150,top=Math.max(28,height/2-105);
        name=field(left,top+12,300,80,n);description=field(left,top+47,300,512,d);
        x=field(left,top+82,96,12,xx);y=field(left+102,top+82,96,12,yy);z=field(left+204,top+82,96,12,zz);
        addRenderableWidget(Button.builder(Component.literal("Icon: "+icon),b->{icon=Icon.values()[(icon.ordinal()+1)%Icon.values().length];b.setMessage(Component.literal("Icon: "+icon));}).bounds(left,top+109,146,20).build());
        addRenderableWidget(Button.builder(Component.literal(String.format("Color #%06X",color)),b->{
            int at=0;for(int i=0;i<COLORS.length;i++)if(COLORS[i]==color)at=i;
            color=COLORS[(at+1)%COLORS.length];b.setMessage(Component.literal(String.format("Color #%06X",color)));
        }).bounds(left+154,top+109,146,20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"),b->save()).bounds(left,top+138,96,20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"),b->onClose()).bounds(left+102,top+138,96,20).build());
        if(original!=null)addRenderableWidget(Button.builder(Component.literal(deleteConfirmation?"Confirm":"Delete"),b->{if(waiting)return;if(!deleteConfirmation){deleteConfirmation=true;b.setMessage(Component.literal("Confirm"));return;}submit(new Change(Action.DELETE,id,null));}).bounds(left+204,top+138,96,20).build());
        setInitialFocus(name);
    }
    private void save(){
        if(waiting)return;
        try{var m=new Marker(id,Integer.parseInt(x.getValue()),Integer.parseInt(y.getValue()),Integer.parseInt(z.getValue()),name.getValue(),description.getValue(),icon,color);
            submit(new Change(Action.UPSERT,id,m));}
        catch(IllegalArgumentException e){error="Enter a name and valid XYZ coordinates.";}
    }
    private void submit(Change change){
        if(waiting)return;
        receipt=session.markers.completed;
        if(session.markers.change(change)){waiting=true;error=null;}
        else error="Markers unavailable or busy.";
    }
    @Override public void tick(){
        if(waiting&&session.markers.completed!=receipt){
            waiting=false;if(session.markers.success){minecraft.setScreen(parent);return;}
            error=session.markers.error;
        }
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(!waiting)minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,0xff20262d);
        int left=width/2-150,top=Math.max(28,height/2-105);
        g.drawCenteredString(font,title,width/2,top-15,0xffffffff);
        g.drawString(font,"Name",left,top,0xffb9c6ce);
        g.drawString(font,"Description",left,top+35,0xffb9c6ce);
        g.drawString(font,"X",left,top+70,0xffb9c6ce);g.drawString(font,"Y",left+102,top+70,0xffb9c6ce);g.drawString(font,"Z",left+204,top+70,0xffb9c6ce);
        super.render(g,mx,my,partial);
        String msg=waiting?"Saving...":error!=null?error:approximate?"Coarse map position; verify coordinates before saving.":"";
        g.drawCenteredString(font,font.plainSubstrByWidth(msg,Math.max(1,width-12)),width/2,top+169,error==null?0xffc5ccd0:0xffff9999);
    }
}
