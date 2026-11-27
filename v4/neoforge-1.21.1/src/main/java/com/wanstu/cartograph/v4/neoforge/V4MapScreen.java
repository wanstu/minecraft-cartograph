package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import com.wanstu.cartograph.v4.MarkerStore.Marker;
import com.wanstu.cartograph.v4.MapLayers.Layer;
import com.wanstu.cartograph.v4.MapHudLayout.Action;
import com.wanstu.cartograph.v4.MapViewOptions.Choice;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** One camera for flat and raised maps; angle changes never alter geography. */
final class V4MapScreen extends Screen {
    private final V4MapSession session;
    private final V4LayerPanel layerPanel;
    private final V4DisplayPanel displayPanel;
    private final V4ViewPanel viewPanel=new V4ViewPanel();
    private final V4ContextMenu contextMenu=new V4ContextMenu();
    private final MapHudLayout.Gesture gesture=new MapHudLayout.Gesture();
    private MapHudLayout hud;
    private int hudWidth,hudHeight;
    private double hudScale;
    private MapDisplaySettings.Ui ui(){return MapDisplaySettings.Ui.of(session.display,width,height);}
    MapDisplaySettings displaySettings(){return session.display;}
    private boolean diagnostics,hudDiagnostics;
    private MapCamera camera,lastCamera;
    private MapProjection projection;
    private MapViewport.Plan plan;
    private int lastWidth,lastHeight;
    private MapRayPicker.Hit picked;
    private record DrawnMarker(Marker marker,int x,int y,int radius){}
    private final List<DrawnMarker> drawnMarkers=new ArrayList<>();
    private final MapLabelLayout labelLayout=new MapLabelLayout();
    private record TextLabel(String text,int x,int y,int color,int priority){}
    private final List<TextLabel> pendingLabels=new ArrayList<>(144);
    private String structureStatus="";
    private boolean selectingRegion;
    private int[] regionCorner;
    V4MapScreen(V4MapSession session,double x,double z){
        super(Component.literal("Cartograph V4"));this.session=session;layerPanel=new V4LayerPanel(session.layers);displayPanel=new V4DisplayPanel(session.display);
        var player=net.minecraft.client.Minecraft.getInstance().player;
        camera=session.camera==null?new MapCamera(x,player==null?64:player.getY(),z,1,0,90):session.camera;
        session.camera=camera;
    }
    @Override protected void init(){gesture.cancel();layerPanel.release();viewPanel.release();displayPanel.release();contextMenu.close();contextMenu.release();hud=null;}
    @Override public boolean isPauseScreen(){return false;}
    private MapHudLayout hud(){
        var ui=ui();
        if(hud==null||hudWidth!=width||hudHeight!=height||hudDiagnostics!=diagnostics||hudScale!=ui.scale()){hud=MapHudLayout.scaled(ui,diagnostics);hudWidth=width;hudHeight=height;hudDiagnostics=diagnostics;hudScale=ui.scale();}
        return hud;
    }
    private static double clamp(double v){return Math.max(-29_999_999,Math.min(29_999_999,v));}
    private double minScale(){return Math.max(3.0/1024,Math.max(width,height)/(1024.0*32*12));}
    private void setCamera(MapCamera c){
        camera=new MapCamera(clamp(c.x()),c.y(),clamp(c.z()),c.pixelsPerBlock(),c.yawDegrees(),c.elevationDegrees());
        session.camera=camera;
    }
    private void updatePlan(){
        double scale=Math.max(minScale(),Math.min(16,camera.pixelsPerBlock()));
        if(scale!=camera.pixelsPerBlock())setCamera(new MapCamera(camera.x(),camera.y(),camera.z(),scale,camera.yawDegrees(),camera.elevationDegrees()));
        if(plan==null||!camera.equals(lastCamera)||width!=lastWidth||height!=lastHeight){
            projection=new MapProjection(camera);
            double minY=minecraft.level==null?-64:minecraft.level.getMinBuildHeight(),maxY=minecraft.level==null?320:minecraft.level.getMaxBuildHeight();
            plan=projection.plan(width,height,minY,maxY,camera.elevationDegrees()==90?384:64);
            lastCamera=camera;lastWidth=width;lastHeight=height;
        }
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        graphics.fill(0,0,width,height,0xff242b30);
        if(minecraft.level==null||!session.dimension.equals(minecraft.level.dimension().location().toString())||
            session.io!=CartographV4Dev.localPresentation(minecraft.getSingleplayerServer()))return;
        updatePlan();session.prepare(plan.keys());
        session.draw(graphics,plan.keys(),camera,projection,width,height,hud());
        labelLayout.begin(hud());pendingLabels.clear();
        drawRegions(graphics,mouseX,mouseY);
        if(minecraft!=null&&minecraft.player!=null){
            var player=minecraft.player;
            var position=player.getPosition(partialTick);
            var p=projection.project(position.x,position.y,position.z);
            double px=width/2.0+p.x(),py=height/2.0+p.y();
            if(px>=0&&px<width&&py>=0&&py<height&&
                labelLayout.reserve((int)Math.round(px),(int)Math.round(py),PlayerHeading.RESERVE,PlayerHeading.RESERVE)){
                session.icons.player(px,py,PlayerHeading.direction(camera,player.getViewYRot(partialTick)));
            }
        }
        drawMarkers(graphics);
        drawStructures(graphics);
        session.icons.flush(graphics);
        drawMapLabels(graphics);
        session.drawContourLabels(graphics,camera,projection,width,height,hud(),labelLayout);
        var ui=ui();int mx=(int)Math.floor(ui.logical(mouseX)),my=(int)Math.floor(ui.logical(mouseY));
        graphics.flush();graphics.pose().pushPose();
        try{
            graphics.pose().scale((float)ui.scale(),(float)ui.scale(),1);
            drawHud(graphics,mx,my,ui.width(),ui.height());
            layerPanel.render(graphics,font,ui.width(),ui.height(),mx,my);
            viewPanel.render(graphics,font,ui.width(),ui.height(),mx,my,session.style,camera,selectingRegion);
            displayPanel.render(graphics,font,ui.width(),ui.height(),mx,my,ui.scale());
            contextMenu.render(graphics,font,ui.width(),ui.height(),mx,my,session.icons);
            session.icons.flush(graphics);graphics.flush();
        }finally{graphics.pose().popPose();}
    }
    private static String tr(String key,Object... args){return Component.translatable("cartograph_v4.hud."+key,args).getString();}
    private void activate(Action action){
        gesture.cancel();contextMenu.close();
        switch(action){
            case LAYERS->{viewPanel.close();displayPanel.close();layerPanel.toggle();}
            case VIEW->{layerPanel.close();displayPanel.close();viewPanel.toggle(hud());}
            case SETTINGS->{layerPanel.close();viewPanel.close();displayPanel.toggle(hud());}
            case MARKERS->minecraft.setScreen(new V4MarkerManager(this,session));
            case REGIONS->minecraft.setScreen(new V4RegionManager(this,session.regions));
            case DIAGNOSTICS->diagnostics=!diagnostics;
            case ZOOM_IN->zoomCenter(1.25);
            case ZOOM_OUT->zoomCenter(.8);
            case CENTER->centerPlayer();
            case NORTH->setCamera(camera.withAngles(0,90));
            case CLOSE->onClose();
        }
    }
    private void applyView(Choice choice){
        var next=MapViewOptions.apply(new MapViewOptions.Selection(session.style,camera),choice,selectingRegion);
        session.style=next.style();
        if(!camera.equals(next.camera()))setCamera(next.camera());
    }
    private void centerPlayer(){
        if(minecraft!=null&&minecraft.player!=null)setCamera(new MapCamera(minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));
    }
    private void zoomCenter(double factor){
        setCamera(camera.zoomAt(Math.max(minScale(),Math.min(16,camera.pixelsPerBlock()*factor)),new MapCamera.World(camera.x(),camera.y(),camera.z())));
    }
    private static void panel(GuiGraphics g,MapHudLayout.Rect r,int color){if(r.w()>0&&r.h()>0)g.fill(r.x(),r.y(),r.x()+r.w(),r.y()+r.h(),color);}
    private void drawHud(GuiGraphics g,int mx,int my,int width,int height){
        var layout=hud();
        panel(g,layout.header(),0xef192329);panel(g,layout.status(),0xe9192329);
        int available=Math.max(1,width-38);
        g.drawString(font,font.plainSubstrByWidth("Cartograph  |  "+session.dimension,available),8,5,0xffecf2f5);
        String coords=tr("center_coords",(int)Math.floor(camera.x()),(int)Math.floor(camera.z()));
        String style=tr("style."+session.style.name().toLowerCase(Locale.ROOT));
        g.drawString(font,font.plainSubstrByWidth(coords+"  |  "+style+"  |  "+(int)camera.elevationDegrees()+"°",available),8,18,0xffbacbd4);
        String status=selectingRegion?tr(regionCorner==null?"first_corner":"second_corner"):
            session.markers.error!=null?session.markers.error:session.regions.error!=null?session.regions.error:
            session.markers.busy()||session.regions.busy()?tr("loading"):tr("hint");
        if(height>=18)g.drawString(font,font.plainSubstrByWidth(status,Math.max(1,width-12)),6,height-13,0xffc7d6df);
        var d=layout.debug();
        if(d.w()>0){
            panel(g,d,0xf3212e37);
            String[] lines={"LOD "+plan.lod()+" | pages "+session.exactPages()+"+"+session.fallbackPages()+"/"+plan.keys().size(),
                "Draw "+session.drawCalls()+" | quads "+session.quads(),
                "Tex "+session.uploads()+" | mesh "+session.meshUploads(),
                session.contourStatus(),structureStatus,
                picked==null?"Pick —":"Pick "+(int)Math.floor(picked.world().x())+" "+(int)Math.floor(picked.world().y())+" "+(int)Math.floor(picked.world().z())+" | LOD "+picked.lod()};
            for(int i=0;i<lines.length&&i*11+14<=d.h();i++)g.drawString(font,font.plainSubstrByWidth(lines[i],Math.max(1,d.w()-12)),d.x()+6,d.y()+5+i*11,0xffc3d4df);
        }
        Action hovered=null;MapHudLayout.Rect hoverRect=null;
        for(var control:layout.controls()){
            var r=control.bounds();boolean hover=r.contains(mx,my);
            boolean active=control.action()==Action.LAYERS&&layerPanel.isOpen()||control.action()==Action.VIEW&&viewPanel.isOpen()||control.action()==Action.SETTINGS&&displayPanel.isOpen()||control.action()==Action.DIAGNOSTICS&&diagnostics;
            panel(g,r,active?0xff405a69:hover?0xff374954:0xeb202e37);
            g.renderOutline(r.x(),r.y(),r.w(),r.h(),0xff627b8a);
            session.icons.draw(MapIcons.toolbar(control.action()),r.x()+r.w()/2,r.y()+r.h()/2,Math.max(6,Math.min(16,r.w()-4)),0xffe4eef4);
            if(hover){hovered=control.action();hoverRect=r;}
        }
        session.icons.flush(g);
        if(hovered!=null&&!layerPanel.isOpen()&&!viewPanel.isOpen()&&!contextMenu.isOpen()&&!displayPanel.isOpen()){
            String label=font.plainSubstrByWidth(tr(hovered.name().toLowerCase(Locale.ROOT)),Math.max(1,width-12));
            int w=font.width(label)+8,x=Math.max(0,Math.min(width-w,hoverRect.x()+hoverRect.w()+4)),y=Math.max(32,Math.min(height-35,hoverRect.y()));
            if(hoverRect.x()>width/2)x=Math.max(0,hoverRect.x()-w-4);
            g.fill(x,y,x+w,y+15,0xff182229);g.drawString(font,label,x+4,y+4,0xffe4eef4);
        }
    }
    void centerOn(Marker m){setCamera(new MapCamera(m.x(),m.y(),m.z(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));}
    private void drawMarkers(GuiGraphics g){
        drawnMarkers.clear();
        var snapshot=session.markers.snapshot;
        if(!session.layers.visible(Layer.MARKERS)||snapshot==null)return;
        int size=session.display.markerSize(camera.pixelsPerBlock()),radius=session.display.markerRadius(camera.pixelsPerBlock());
        for(Marker m:snapshot.active()){
            var p=projection.project(m.x()+.5,m.y()+1,m.z()+.5);
            int x=(int)Math.round(width/2.0+p.x()),y=(int)Math.round(height/2.0+p.y());
            if(drawnMarkers.size()>=128)break;
            if(!labelLayout.reserve(x,y,radius,radius))continue;
            drawnMarkers.add(new DrawnMarker(m,x,y,radius));
            int color=0xff000000|m.color();
            g.fill(x-radius+1,y-radius+1,x+radius,y+radius,0xc51b2228);
            session.icons.draw(MapIcons.marker(m.icon()),x,y,size,color);
            if(drawnMarkers.size()<=48)pendingLabels.add(new TextLabel(m.name(),x,y+radius+2,0xfff2f5f7,0));
        }
    }
    private void drawStructures(GuiGraphics g){
        if(!session.layers.structuresActive()){structureStatus="Structures off";return;}
        boolean raised=camera.elevationDegrees()!=90;
        double minY=raised&&minecraft.level!=null?minecraft.level.getMinBuildHeight():camera.y();
        double maxY=raised&&minecraft.level!=null?minecraft.level.getMaxBuildHeight():camera.y();
        var bounds=StructureView.bounds(projection.bounds(width,height,minY,maxY));
        var result=session.structureView.tick(bounds,session.io.structureRevision(session.dimension),session.layers.categoryMask());
        structureStatus=result.limited()?"Structure view limited; zoom in":session.structureView.error()!=null?session.structureView.error():session.structureView.busy()?"Structures loading":"Structures "+result.starts().size();
        int displayed=0,size=session.display.markerSize(camera.pixelsPerBlock()),radius=session.display.markerRadius(camera.pixelsPerBlock());
        for(var start:result.starts()){
            double yAnchor=camera.y();
            boolean approximate=!start.exactCenter();
            if(raised){
                var heightSample=session.structureHeight(plan.lod(),start.x(),start.z());
                if(heightSample==null)continue; // No observed, currently rendered surface: no fabricated altitude.
                yAnchor=heightSample.y();
                approximate|=!heightSample.exact();
            }
            var projected=projection.project(start.x()+.5,yAnchor,start.z()+.5);
            int x=(int)Math.round(width/2.0+projected.x());
            int y=(int)Math.round(height/2.0+projected.y());
            if(displayed>=96)break;
            if(!labelLayout.reserve(x,y,radius,radius))continue;
            displayed++;
            var description=StructureCatalog.describe(start.type());
            g.fill(x-radius+1,y-radius+1,x+radius,y+radius,0xc520262d);
            session.icons.draw(MapIcons.structure(description.category()),x,y,size,0xff9cdaef);
            if(displayed<=32){
                String name=Component.translatableWithFallback(description.translationKey(),description.fallback()).getString();
                if(approximate)name="~ "+name;
                pendingLabels.add(new TextLabel(name,x,y+radius+2,0xffb7e1ee,1));
            }
        }
    }
    private void drawMapLabels(GuiGraphics g){
        pendingLabels.sort(Comparator.comparingInt(TextLabel::priority));
        for(var label:pendingLabels){
            String name=font.plainSubstrByWidth(label.text(),120);
            double scale=session.display.labels();
            int halfW=(int)Math.ceil(font.width(name)*scale/2)+2,halfH=(int)Math.ceil(font.lineHeight*scale/2)+1,centerY=label.y()+halfH;
            if(name.isEmpty()||!labelLayout.reserve(label.x(),centerY,halfW,halfH))continue;
            g.fill(label.x()-halfW,centerY-halfH,label.x()+halfW+1,centerY+halfH+1,0x981b2329);
            g.pose().pushPose();
            try{g.pose().translate(label.x(),label.y()+1,0);g.pose().scale((float)scale,(float)scale,1);g.drawCenteredString(font,name,0,0,label.color());}
            finally{g.pose().popPose();}
        }
    }
    void beginRegion(){selectingRegion=true;regionCorner=null;setCamera(camera.withAngles(camera.yawDegrees(),90));}
    void centerRegion(NamedRegion r){setCamera(new MapCamera(r.centerX(),camera.y(),r.centerZ(),camera.pixelsPerBlock(),camera.yawDegrees(),90));}
    private int[] blockAt(double mx,double my){
        var w=camera.atHeight(mx-width/2.0,my-height/2.0,camera.y());
        return new int[]{(int)Math.floor(clamp(w.x())),(int)Math.floor(clamp(w.z()))};
    }
    private void drawRegions(GuiGraphics g,int mx,int my){
        if(camera.elevationDegrees()!=90)return;
        var snapshot=session.regions.snapshot;
        if(session.layers.visible(Layer.REGIONS)&&snapshot!=null){
            var b=projection.bounds(width,height,camera.y(),camera.y());
            var visible=snapshot.active().stream().filter(r->r.intersects(b))
                .sorted(Comparator.comparingLong(NamedRegion::area).reversed().thenComparing(NamedRegion::id)).limit(64).toList();
            for(var r:visible)drawRegionRect(g,r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.color(),r.name());
        }
        if(selectingRegion&&regionCorner!=null&&mx>=0&&mx<width&&my>=0&&my<height&&!hud().blocks(ui().logical(mx),ui().logical(my))){
            int[] end=blockAt(mx,my);drawRegionRect(g,Math.min(regionCorner[0],end[0]),Math.min(regionCorner[1],end[1]),
                Math.max(regionCorner[0],end[0]),Math.max(regionCorner[1],end[1]),0xffcc55,"");
        }
    }
    private void drawRegionRect(GuiGraphics g,int x0,int z0,int x1,int z1,int color,String name){
        double cx=(x0+(double)x1+1)/2,cz=(z0+(double)z1+1)/2;
        double px=width/2.0+projection.screenX(cx,cz),py=height/2.0+projection.screenY(cx,camera.y(),cz);
        int hw=Math.max(1,(int)Math.ceil((x1-(double)x0+1)*camera.pixelsPerBlock()/2)),
            hh=Math.max(1,(int)Math.ceil((z1-(double)z0+1)*camera.pixelsPerBlock()/2));
        g.pose().pushPose();
        try{
            g.pose().translate(px,py,0);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float)-camera.yawDegrees()));
            g.fill(-hw,-hh,hw,hh,0x18000000|color);
            g.renderOutline(-hw,-hh,2*hw,2*hh,0xc0000000|color);
        }finally{g.pose().popPose();}
        if(!name.isEmpty()&&hw>=15&&hh>=8&&px>=0&&px<width&&py>=0&&py<height)
            pendingLabels.add(new TextLabel(name,(int)px,(int)py-4,0xffe1e8ec,2));
    }
    @Override public boolean mouseClicked(double mx,double my,int button){
        gesture.press(false,button);
        var ui=ui();double ux=ui.logical(mx),uy=ui.logical(my);
        if(contextMenu.click(ux,uy,button,ui.width(),ui.height(),this::applyContext))return true;
        if(displayPanel.click(ux,uy,button,ui.width(),ui.height()))return true;
        if(viewPanel.click(ux,uy,button,ui.width(),ui.height(),selectingRegion,this::applyView)||layerPanel.click(ux,uy,button,ui.width(),ui.height()))return true;
        var action=hud().actionAt(ux,uy);
        if(action!=null){if(button==0)activate(action);return true;}
        if(hud().blocks(ux,uy))return true;
        if(button==0){gesture.press(true,button);return true;}
        if(button!=1)return super.mouseClicked(mx,my,button);
        if(selectingRegion){
            if(camera.elevationDegrees()!=90||session.regions.busy()||session.regions.snapshot==null)return true;
            int[] end=blockAt(mx,my);if(regionCorner==null){regionCorner=end;return true;}
            minecraft.setScreen(new V4RegionEditor(this,session.regions,null,regionCorner[0],regionCorner[1],end[0],end[1]));selectingRegion=false;regionCorner=null;return true;
        }
        Marker marker=null;
        if(session.markers.snapshot!=null&&!session.markers.busy()&&camera.equals(lastCamera)&&width==lastWidth&&height==lastHeight)
            for(var d:drawnMarkers)if(Math.abs(d.x-mx)<=d.radius&&Math.abs(d.y-my)<=d.radius){marker=d.marker;break;}
        picked=marker==null?session.pick(camera,mx-width/2.0,my-height/2.0):null;
        int[] at=blockAt(mx,my);
        boolean regionReady=session.regions.snapshot!=null&&!session.regions.busy()&&camera.elevationDegrees()==90;
        gesture.cancel();
        contextMenu.open((int)ux,(int)uy,new V4ContextMenu.Target(marker,picked,at[0],at[1]),session.markers.snapshot!=null&&!session.markers.busy(),regionReady);
        return true;
    }
    private void applyContext(V4ContextMenu.Invocation invocation){
        var target=invocation.target();var marker=target.marker();var hit=target.hit();
        if(invocation.action()==MapContextLayout.Action.NEW_REGION){if(session.regions.snapshot!=null&&!session.regions.busy()){beginRegion();regionCorner=new int[]{target.x(),target.z()};}return;}
        if(marker==null&&hit==null)return;
        var w=marker==null?hit.world():new MapCamera.World(marker.x(),marker.y(),marker.z());
        int x=(int)Math.floor(w.x()),y=(int)Math.floor(w.y()),z=(int)Math.floor(w.z());
        switch(invocation.action()){
            case ADD,EDIT->{
                if(session.markers.snapshot!=null&&!session.markers.busy()&&(marker==null||session.markers.snapshot.active().contains(marker)))
                    minecraft.setScreen(new V4MarkerEditor(this,session,marker,x,y,z,marker==null&&(hit.lod()!=0||hit.side())));
            }
            case CENTER->setCamera(new MapCamera(w.x(),w.y(),w.z(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));
            case COPY->minecraft.keyboardHandler.setClipboard(x+", "+y+", "+z);
            default->{}
        }
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        var ui=ui();double ux=ui.logical(dx),uy=ui.logical(dy);
        if(contextMenu.drag()||displayPanel.drag(ux,uy,ui.width(),ui.height())||viewPanel.drag(ux,uy,ui.width(),ui.height())||layerPanel.drag(ux,uy,ui.width(),ui.height()))return true;
        if(button!=0)return super.mouseDragged(mx,my,button,dx,dy);
        if(!gesture.canPan(button))return true;
        var p=camera.atHeight(-dx,-dy,camera.y());
        setCamera(new MapCamera(p.x(),camera.y(),p.z(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));
        return true;
    }
    @Override public boolean mouseReleased(double mx,double my,int button){
        gesture.release(button);
        boolean contextRelease=contextMenu.release(),viewRelease=viewPanel.release(),layerRelease=layerPanel.release(),displayRelease=displayPanel.release();
        if(contextRelease||viewRelease||layerRelease||displayRelease)return true;
        return super.mouseReleased(mx,my,button);
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){
        var ui=ui();
        if(displayPanel.isOpen()||contextMenu.scroll(vertical,ui.width(),ui.height())||viewPanel.scroll(vertical,ui.width(),ui.height())||layerPanel.scroll(vertical,ui.width(),ui.height())||hud().blocks(ui.logical(mx),ui.logical(my)))return true;
        double next=Math.max(minScale(),Math.min(16,camera.pixelsPerBlock()*Math.pow(1.25,vertical)));
        var hit=session.pick(camera,mx-width/2.0,my-height/2.0);
        var anchor=hit==null?camera.atHeight(mx-width/2.0,my-height/2.0,camera.y()):hit.world();
        setCamera(camera.zoomAt(next,anchor));return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(contextMenu.isOpen()){if(key==GLFW.GLFW_KEY_ESCAPE)contextMenu.close();return true;}
        if(key==GLFW.GLFW_KEY_O){activate(Action.SETTINGS);return true;}
        if(key==GLFW.GLFW_KEY_P){activate(Action.VIEW);return true;}
        if(key==GLFW.GLFW_KEY_L){activate(Action.LAYERS);return true;}
        if(displayPanel.isOpen()){if(key==GLFW.GLFW_KEY_ESCAPE)displayPanel.close();return true;}
        if(viewPanel.isOpen()){if(key==GLFW.GLFW_KEY_ESCAPE)viewPanel.close();return true;}
        if(layerPanel.isOpen()){if(key==GLFW.GLFW_KEY_ESCAPE)layerPanel.close();return true;}
        if(key==GLFW.GLFW_KEY_F3){diagnostics=!diagnostics;gesture.cancel();return true;}
        if(selectingRegion&&key==GLFW.GLFW_KEY_ESCAPE){selectingRegion=false;regionCorner=null;return true;}
        if(selectingRegion&&key==GLFW.GLFW_KEY_T)return true;
        if(key==GLFW.GLFW_KEY_G){minecraft.setScreen(new V4RegionManager(this,session.regions));return true;}
        if(key==GLFW.GLFW_KEY_H){session.layers.toggle(Layer.REGIONS);return true;}
        if(key==GLFW.GLFW_KEY_S){session.layers.toggle(Layer.STRUCTURES);return true;}
        if(key==GLFW.GLFW_KEY_B){minecraft.setScreen(new V4MarkerManager(this,session));return true;}
        if(key==GLFW.GLFW_KEY_V){session.layers.toggle(Layer.MARKERS);return true;}
        if(key==GLFW.GLFW_KEY_C){session.layers.toggle(Layer.CONTOURS);return true;}
        if(key==GLFW.GLFW_KEY_M){onClose();return true;}
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_5){applyView(MapViewOptions.styleAt(key-GLFW.GLFW_KEY_1));return true;}
        if(key==GLFW.GLFW_KEY_R){centerPlayer();return true;}
        if(key==GLFW.GLFW_KEY_T){applyView(MapViewOptions.nextTilt(camera));return true;}
        if(key==GLFW.GLFW_KEY_Q||key==GLFW.GLFW_KEY_E){applyView(key==GLFW.GLFW_KEY_Q?Choice.ROTATE_LEFT:Choice.ROTATE_RIGHT);return true;}
        if(key==GLFW.GLFW_KEY_N){setCamera(camera.withAngles(0,90));return true;}
        return super.keyPressed(key,scan,modifiers);
    }
}
