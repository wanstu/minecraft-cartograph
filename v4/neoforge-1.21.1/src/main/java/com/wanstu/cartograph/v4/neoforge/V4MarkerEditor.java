package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.MarkerStore.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import java.util.*;

final class V4MarkerEditor extends V4AnnotationPanel {
    final V4MapSession session;
    private final Marker original;
    private final UUID id;
    private final int initialX,initialY,initialZ;
    private final boolean approximate;
    private EditBox name,description,x,y,z;
    private Icon icon;
    private int color;
    private static final int[] COLORS={0xffcc55,0xffffff,0x72b9e8,0x76c78a,0xec7373,0xc1a1ed};
    private boolean waiting,deleteConfirmation;
    private long receipt;
    private String error;
    V4MarkerEditor(V4MapScreen parent,V4MapSession session,Marker original,int x,int y,int z,boolean approximate){
        super(parent,label(original==null?"new_marker":"edit_marker"),4);this.session=session;this.original=original;
        id=original==null?UUID.randomUUID():original.id();
        initialX=x;initialY=y;initialZ=z;this.approximate=approximate;
        icon=original==null?Icon.POINT:original.icon();color=original==null?COLORS[0]:original.color();
    }
    private Component iconLabel(){return Component.translatable("cartograph_v4.editor.icon",label("icon."+icon.name().toLowerCase(Locale.ROOT)));}
    private Component colorLabel(){return Component.translatable("cartograph_v4.editor.color",String.format("#%06X",color));}
    @Override void buildFields(){
        name=field(0,0,1,"name",80,name==null?(original==null?"":original.name()):name.getValue());
        description=field(1,0,1,"description",512,description==null?(original==null?"":original.description()):description.getValue());
        x=field(2,0,3,"x",12,x==null?Integer.toString(initialX):x.getValue());
        y=field(2,1,3,"y",12,y==null?Integer.toString(initialY):y.getValue());
        z=field(2,2,3,"z",12,z==null?Integer.toString(initialZ):z.getValue());
        button(3,0,2,iconLabel(),b->{icon=Icon.values()[(icon.ordinal()+1)%Icon.values().length];b.setMessage(iconLabel());});
        button(3,1,2,colorLabel(),b->{int at=-1;for(int i=0;i<COLORS.length;i++)if(COLORS[i]==color)at=i;color=COLORS[(at+1)%COLORS.length];b.setMessage(colorLabel());});
        int count=original==null?2:3;
        button(-1,0,count,label("save"),b->save());
        button(-1,1,count,label("cancel"),b->onClose());
        if(original!=null)button(-1,2,count,label(deleteConfirmation?"confirm":"delete"),b->{if(waiting)return;if(!deleteConfirmation){deleteConfirmation=true;b.setMessage(label("confirm"));return;}submit(new Change(Action.DELETE,id,null));});
    }
    private void save(){
        if(waiting)return;
        try{var m=new Marker(id,Integer.parseInt(x.getValue()),Integer.parseInt(y.getValue()),Integer.parseInt(z.getValue()),name.getValue(),description.getValue(),icon,color);
            submit(new Change(Action.UPSERT,id,m));}
        catch(IllegalArgumentException e){error=label("invalid_marker").getString();}
    }
    private void submit(Change change){
        if(waiting)return;
        receipt=session.markers.completed;
        if(session.markers.change(change)){waiting=true;error=null;}
        else error=label("unavailable").getString();
    }
    @Override public void tick(){
        if(waiting&&session.markers.completed!=receipt){
            waiting=false;if(session.markers.success){minecraft.setScreen(parent);return;}
            error=session.markers.error;
        }
    }
    @Override boolean waiting(){return waiting;}
    @Override boolean hasError(){return error!=null;}
    @Override String status(){return waiting?label("saving").getString():error!=null?error:approximate?label("approximate").getString():"";}
}
