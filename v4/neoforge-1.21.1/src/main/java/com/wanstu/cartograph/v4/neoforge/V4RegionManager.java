package com.wanstu.cartograph.v4.neoforge;
import com.wanstu.cartograph.v4.*;
import com.wanstu.cartograph.v4.MarkerStore.Action;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

final class V4RegionManager extends V4ScaledScreen{
    private final V4MapScreen parent;
    private final V4RegionState state;
    private int page;
    private boolean trash,waiting;
    private long seen=-1;
    private UUID purge;
    V4RegionManager(V4MapScreen parent,V4RegionState state){super(parent,Component.literal("Named regions"));this.parent=parent;this.state=state;}
    @Override protected void initUi(){
        int w=Math.min(300,ui.width()-16),left=(ui.width()-w)/2,bw=(w-6)/4,rows=Math.max(1,Math.min(8,(ui.height()-90)/24));
        var snapshot=state.snapshot;
        List<NamedRegion> list=snapshot==null?List.of():trash?snapshot.trash():snapshot.active();
        int last=Math.max(0,(list.size()-1)/rows);page=Math.min(page,last);
        addRenderableWidget(Button.builder(Component.literal(trash?"Trash":"Active"),b->{trash=!trash;page=0;purge=null;rebuildWidgets();}).bounds(left,10,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("New"),b->{if(!state.busy()&&state.snapshot!=null){parent.beginRegion();minecraft.setScreen(parent);}}).bounds(left+bw+2,10,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload"),b->state.reload()).bounds(left+2*(bw+2),10,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(left+3*(bw+2),10,bw,20).build());
        for(int i=page*rows;i<Math.min(list.size(),(page+1)*rows);i++){
            var r=list.get(i);int y=35+(i-page*rows)*24;
            String label=font.plainSubstrByWidth(r.name(),Math.max(1,2*bw-8));
            addRenderableWidget(Button.builder(Component.literal(label),b->{if(!trash&&!state.busy())minecraft.setScreen(new V4RegionEditor(parent,state,r,r.minX(),r.minZ(),r.maxX(),r.maxZ()));}).bounds(left,y,2*bw,20).build());
            if(trash){
                addRenderableWidget(Button.builder(Component.literal("Restore"),b->{purge=null;if(state.change(new NamedRegionStore.Change(Action.RESTORE,r.id(),null)))waiting=true;}).bounds(left+2*(bw+2),y,bw,20).build());
                addRenderableWidget(Button.builder(Component.literal(r.id().equals(purge)?"Confirm":"Purge"),b->{
                    if(state.busy())return;if(!r.id().equals(purge)){purge=r.id();rebuildWidgets();return;}
                    if(state.change(new NamedRegionStore.Change(Action.PURGE,r.id(),null))){waiting=true;purge=null;rebuildWidgets();}
                }).bounds(left+3*(bw+2),y,bw,20).build());
            }else addRenderableWidget(Button.builder(Component.literal("Center"),b->{parent.centerRegion(r);minecraft.setScreen(parent);}).bounds(left+2*(bw+2),y,2*bw,20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Previous"),b->{if(page>0){page--;purge=null;rebuildWidgets();}}).bounds(left,ui.height()-46,bw,20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"),b->{if(page<last){page++;purge=null;rebuildWidgets();}}).bounds(left+3*(bw+2),ui.height()-46,bw,20).build());
        seen=state.completed;
    }
    @Override public void tick(){if(seen!=state.completed){waiting=false;rebuildWidgets();}}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(!waiting)minecraft.setScreen(parent);}
    @Override protected void renderUi(GuiGraphics g,int mx,int my,float partial){
        super.renderUi(g,mx,my,partial);
        g.drawCenteredString(font,"Page "+(page+1),ui.width()/2,ui.height()-40,0xffd3dce0);
        String msg=state.busy()?"Loading / saving...":state.error!=null?state.error:purge!=null?"Confirm permanent deletion":"New: choose two corners on map";
        g.drawCenteredString(font,font.plainSubstrByWidth(msg,Math.max(1,ui.width()-10)),ui.width()/2,ui.height()-18,0xffc1cad0);
    }
}
