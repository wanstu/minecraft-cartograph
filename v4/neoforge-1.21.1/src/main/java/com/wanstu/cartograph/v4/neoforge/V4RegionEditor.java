package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import com.wanstu.cartograph.v4.MarkerStore.Action;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import java.util.*;

final class V4RegionEditor extends V4AnnotationPanel{
    private final V4RegionState state;
    private final NamedRegion original;
    private final int[] bounds;
    private final UUID id;
    private EditBox name,description;
    private final EditBox[] coords=new EditBox[4];
    private int color;
    private static final int[] COLORS={0x8cc8ef,0xffcc55,0x76c78a,0xec7373,0xc1a1ed,0xffffff};
    private boolean waiting,deleteConfirm;
    private long receipt;
    private String error;
    V4RegionEditor(V4MapScreen parent,V4RegionState state,NamedRegion original,int x0,int z0,int x1,int z1){
        super(parent,label(original==null?"new_region":"edit_region"),5);
        this.state=state;this.original=original;
        id=original==null?UUID.randomUUID():original.id();bounds=new int[]{x0,z0,x1,z1};
        color=original==null?COLORS[0]:original.color();
    }
    private Component colorLabel(){return Component.translatable("cartograph_v4.editor.color",String.format("#%06X",color));}
    @Override void buildFields(){
        name=field(0,0,1,"name",80,name==null?(original==null?"":original.name()):name.getValue());
        description=field(1,0,1,"description",512,description==null?(original==null?"":original.description()):description.getValue());
        String[] labels={"x1","z1","x2","z2"};
        for(int i=0;i<4;i++)coords[i]=field(2+i/2,i%2,2,labels[i],12,coords[i]==null?Integer.toString(bounds[i]):coords[i].getValue());
        button(4,0,1,colorLabel(),b->{int at=-1;for(int i=0;i<COLORS.length;i++)if(COLORS[i]==color)at=i;color=COLORS[(at+1)%COLORS.length];b.setMessage(colorLabel());});
        int count=original==null?2:3;
        button(-1,0,count,label("save"),b->save());
        button(-1,1,count,label("cancel"),b->onClose());
        if(original!=null)button(-1,2,count,label(deleteConfirm?"confirm":"delete"),b->{if(waiting)return;if(!deleteConfirm){deleteConfirm=true;b.setMessage(label("confirm"));return;}submit(new NamedRegionStore.Change(Action.DELETE,id,null));});
    }
    private void save(){
        if(waiting)return;
        try{var r=NamedRegion.between(id,Integer.parseInt(coords[0].getValue()),Integer.parseInt(coords[1].getValue()),Integer.parseInt(coords[2].getValue()),Integer.parseInt(coords[3].getValue()),name.getValue(),description.getValue(),color);
            submit(new NamedRegionStore.Change(Action.UPSERT,id,r));}
        catch(IllegalArgumentException e){error=label("invalid_region").getString();}
    }
    private void submit(NamedRegionStore.Change c){if(waiting)return;receipt=state.completed;if(state.change(c)){waiting=true;error=null;}else error=label("unavailable").getString();}
    @Override public void tick(){if(waiting&&receipt!=state.completed){waiting=false;if(state.success){minecraft.setScreen(parent);return;}error=state.error;}}
    @Override boolean waiting(){return waiting;}
    @Override boolean hasError(){return error!=null;}
    @Override String status(){return waiting?label("saving").getString():error!=null?error:label("inclusive").getString();}
}
