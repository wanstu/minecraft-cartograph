package com.wanstu.cartograph.v4.neoforge;
import com.wanstu.cartograph.v4.*;
import com.wanstu.cartograph.v4.MarkerStore.Action;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

final class V4RegionEditor extends Screen{
    private final V4MapScreen parent;
    private final V4RegionState state;
    private final NamedRegion original;
    private final int[] bounds;
    private final UUID id;
    private EditBox name,description;
    private final EditBox[] coords=new EditBox[4];
    private int color;
    private final int[] colors={0x8cc8ef,0xffcc55,0x76c78a,0xec7373,0xc1a1ed,0xffffff};
    private boolean waiting,deleteConfirm;
    private long receipt;
    private String error;
    V4RegionEditor(V4MapScreen parent,V4RegionState state,NamedRegion original,int x0,int z0,int x1,int z1){
        super(Component.literal(original==null?"New region":"Edit region"));
        this.parent=parent;this.state=state;this.original=original;
        id=original==null?UUID.randomUUID():original.id();bounds=new int[]{x0,z0,x1,z1};
        color=original==null?colors[0]:original.color();
    }
    private EditBox field(int x,int y,int w,int limit,String value){
        var b=new EditBox(font,x,y,w,20,Component.literal("Region field"));b.setMaxLength(limit);b.setValue(value);return addRenderableWidget(b);
    }
    @Override protected void init(){
        int w=Math.min(300,width-16),left=(width-w)/2,top=Math.max(20,(height-170)/2),half=(w-6)/2;
        name=field(left,top+10,w,80,name==null?(original==null?"":original.name()):name.getValue());
        description=field(left,top+42,w,512,description==null?(original==null?"":original.description()):description.getValue());
        for(int i=0;i<4;i++)coords[i]=field(left+(i%2)*(half+6),top+74+(i/2)*30,half,12,coords[i]==null?Integer.toString(bounds[i]):coords[i].getValue());
        int bw=(w-8)/4;
        addRenderableWidget(Button.builder(Component.literal("Color"),b->{int at=0;for(int i=0;i<colors.length;i++)if(colors[i]==color)at=i;color=colors[(at+1)%colors.length];}).bounds(left,top+136,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Save"),b->save()).bounds(left+bw+2,top+136,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(left+2*(bw+2),top+136,bw,20).build());
        if(original!=null)addRenderableWidget(Button.builder(Component.literal(deleteConfirm?"Confirm":"Delete"),b->{
            if(waiting)return;if(!deleteConfirm){deleteConfirm=true;b.setMessage(Component.literal("Confirm"));return;}
            submit(new NamedRegionStore.Change(Action.DELETE,id,null));
        }).bounds(left+3*(bw+2),top+136,bw,20).build());
        setInitialFocus(name);
    }
    private void save(){
        if(waiting)return;
        try{var r=NamedRegion.between(id,Integer.parseInt(coords[0].getValue()),Integer.parseInt(coords[1].getValue()),
                Integer.parseInt(coords[2].getValue()),Integer.parseInt(coords[3].getValue()),name.getValue(),description.getValue(),color);
            submit(new NamedRegionStore.Change(Action.UPSERT,id,r));}
        catch(IllegalArgumentException e){error="Enter a name and valid X/Z bounds.";}
    }
    private void submit(NamedRegionStore.Change c){if(waiting)return;receipt=state.completed;if(state.change(c)){waiting=true;error=null;}else error="Regions unavailable or busy.";}
    @Override public void tick(){if(waiting&&receipt!=state.completed){waiting=false;if(state.success){minecraft.setScreen(parent);return;}error=state.error;}}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(!waiting)minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        g.fill(0,0,width,height,0xff20262d);
        int w=Math.min(300,width-16),left=(width-w)/2,top=Math.max(20,(height-170)/2),half=(w-6)/2;
        g.drawCenteredString(font,title,width/2,top-13,0xffffffff);
        g.drawString(font,"Name",left,top,0xffc1cad0);g.drawString(font,"Description",left,top+32,0xffc1cad0);
        g.drawString(font,"X1",left,top+64,0xffc1cad0);g.drawString(font,"Z1",left+half+6,top+64,0xffc1cad0);
        g.drawString(font,"X2",left,top+94,0xffc1cad0);g.drawString(font,"Z2",left+half+6,top+94,0xffc1cad0);
        g.fill(left,top+127,left+20,top+133,0xff000000|color);
        super.render(g,mx,my,partial);
        String msg=waiting?"Saving...":error==null?"Inclusive X/Z block bounds":error;
        g.drawCenteredString(font,font.plainSubstrByWidth(msg,Math.max(1,width-10)),width/2,top+160,error==null?0xffc1cad0:0xffff9999);
    }
}
