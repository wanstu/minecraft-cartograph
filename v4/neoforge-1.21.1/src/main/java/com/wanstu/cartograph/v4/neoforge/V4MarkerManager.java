package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.MarkerStore.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Paged active/trash manager; no unbounded widget creation. */
final class V4MarkerManager extends V4ScaledScreen {
    final V4MapScreen parent;
    final V4MapSession session;
    private boolean trash;
    private int page;
    private long seen=-1;
    private boolean waiting;
    private UUID purgeConfirmation;
    V4MarkerManager(V4MapScreen parent,V4MapSession session){
        super(parent,Component.literal("Markers"));this.parent=parent;this.session=session;
    }
    @Override protected void initUi(){
        int left=ui.width()/2-145,top=38,rows=Math.max(1,Math.min(8,(ui.height()-100)/24));
        var snapshot=session.markers.snapshot;
        List<Marker> list=snapshot==null?List.of():trash?snapshot.trash():snapshot.active();
        int last=Math.max(0,(list.size()-1)/rows);page=Math.min(page,last);
        addRenderableWidget(Button.builder(Component.literal(trash?"Trash":"Active"),b->{trash=!trash;page=0;purgeConfirmation=null;rebuildWidgets();}).bounds(left,13,100,20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload"),b->session.markers.reload()).bounds(left+105,13,90,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(left+200,13,90,20).build());
        for(int i=page*rows;i<Math.min(list.size(),(page+1)*rows);i++){
            Marker m=list.get(i);int y=top+(i-page*rows)*24;
            String label=font.plainSubstrByWidth(m.name()+" ("+m.x()+", "+m.y()+", "+m.z()+")",180);
            addRenderableWidget(Button.builder(Component.literal(label),b->{
                if(waiting||trash)return;
                minecraft.setScreen(new V4MarkerEditor(parent,session,m,m.x(),m.y(),m.z(),false));
            }).bounds(left,y,trash?126:190,20).build());
            if(trash){
                addRenderableWidget(Button.builder(Component.literal("Restore"),b->{
                    purgeConfirmation=null;
                    if(!waiting&&session.markers.change(new Change(Action.RESTORE,m.id(),null)))waiting=true;
                }).bounds(left+130,y,78,20).build());
                addRenderableWidget(Button.builder(Component.literal(m.id().equals(purgeConfirmation)?"Confirm":"Purge"),b->{
                    if(waiting||session.markers.busy())return;
                    if(!m.id().equals(purgeConfirmation)){purgeConfirmation=m.id();rebuildWidgets();return;}
                    if(session.markers.change(new Change(Action.PURGE,m.id(),null))){waiting=true;purgeConfirmation=null;rebuildWidgets();}
                }).bounds(left+212,y,78,20).build());
            }
            else addRenderableWidget(Button.builder(Component.literal("Center"),b->{parent.centerOn(m);minecraft.setScreen(parent);}).bounds(left+194,y,96,20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Previous"),b->{if(page>0){page--;purgeConfirmation=null;rebuildWidgets();}}).bounds(left,ui.height()-48,100,20).build());
        addRenderableWidget(Button.builder(Component.literal("Next"),b->{if(page<last){page++;purgeConfirmation=null;rebuildWidgets();}}).bounds(left+190,ui.height()-48,100,20).build());
        seen=session.markers.completed;
    }
    @Override public void tick(){if(seen!=session.markers.completed){waiting=false;rebuildWidgets();}}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(!waiting)minecraft.setScreen(parent);}
    @Override protected void renderUi(GuiGraphics g,int mx,int my,float partial){
        super.renderUi(g,mx,my,partial);
        g.drawCenteredString(font,"Page "+(page+1),ui.width()/2,ui.height()-42,0xffd3dce0);
        String msg=session.markers.busy()?"Loading / saving...":session.markers.error!=null?session.markers.error:purgeConfirmation!=null?"Confirm permanently removes this deleted marker.":"";
        g.drawCenteredString(font,font.plainSubstrByWidth(msg,Math.max(1,ui.width()-12)),ui.width()/2,ui.height()-19,0xffe2b2a8);
    }
}
