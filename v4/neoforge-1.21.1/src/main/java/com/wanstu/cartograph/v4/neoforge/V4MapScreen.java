package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import com.wanstu.cartograph.v4.MarkerStore.Marker;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** One camera for flat and raised maps; angle changes never alter geography. */
final class V4MapScreen extends Screen {
    private final V4MapSession session;
    private MapCamera camera,lastCamera;
    private MapProjection projection;
    private MapViewport.Plan plan;
    private int lastWidth,lastHeight;
    private MapRayPicker.Hit picked;
    private record DrawnMarker(Marker marker,int x,int y){}
    private final List<DrawnMarker> drawnMarkers=new ArrayList<>();
    private String structureStatus="";
    private boolean selectingRegion;
    private int[] regionCorner;
    private static final double[] ANGLES={90,60,45,30};
    V4MapScreen(V4MapSession session,double x,double z){
        super(Component.literal("Cartograph V4 Dev"));this.session=session;
        var player=net.minecraft.client.Minecraft.getInstance().player;
        camera=new MapCamera(x,player==null?64:player.getY(),z,1,session.yaw,session.elevation);
    }
    @Override public boolean isPauseScreen(){return false;}
    private static double clamp(double v){return Math.max(-29_999_999,Math.min(29_999_999,v));}
    private double minScale(){return Math.max(3.0/1024,Math.max(width,height)/(1024.0*32*12));}
    private void setCamera(MapCamera c){
        camera=new MapCamera(clamp(c.x()),c.y(),clamp(c.z()),c.pixelsPerBlock(),c.yawDegrees(),c.elevationDegrees());
        session.yaw=camera.yawDegrees();session.elevation=camera.elevationDegrees();
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
        updatePlan();session.prepare(plan.keys());
        session.draw(graphics,plan.keys(),camera,projection,width,height);
        drawRegions(graphics,mouseX,mouseY);
        if(minecraft!=null&&minecraft.player!=null){
            var p=projection.project(minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ());
            int px=(int)Math.round(width/2.0+p.x()),pz=(int)Math.round(height/2.0+p.y());
            if(px>=0&&px<width&&pz>=0&&pz<height){
                graphics.fill(px-4,pz-4,px+5,pz+5,0xff111111);
                graphics.fill(px-2,pz-2,px+3,pz+3,0xffffcc55);
            }
        }
        drawMarkers(graphics);
        drawStructures(graphics);
        graphics.fill(0,0,width,107,0xde151a20);
        graphics.drawString(font,"Cartograph V4 | "+session.style.name()+" | LOD "+plan.lod(),8,6,0xffffffff);
        graphics.drawString(font,"Drag / scroll | 1-5 styles | R center | Esc close",8,17,0xffc1cad0);
        graphics.drawString(font,"T tilt "+(int)camera.elevationDegrees()+" | Q/E rotate "+(int)camera.yawDegrees()+" | N north-up",8,28,0xffc1cad0);
        graphics.drawString(font,"X "+(int)camera.x()+" Z "+(int)camera.z()+" | pages "+session.exactPages()+"+"+session.fallbackPages()+"/"+plan.keys().size()+" | quads "+session.quads()+" | tex "+session.uploads()+" mesh "+session.meshUploads(),8,39,0xff9fadb8);
        String hit=picked==null?"Right click: add / edit marker":"Pick "+(int)Math.floor(picked.world().x())+" "+(int)Math.floor(picked.world().y())+" "+(int)Math.floor(picked.world().z())+" | LOD "+picked.lod()+(picked.side()?" side":" top");
        graphics.drawString(font,hit,8,50,0xffc1cad0);
        graphics.drawString(font,"C: "+session.contourStatus(),8,61,0xffc1cad0);
        String m=session.markers.busy()?"Markers loading/saving":session.markers.error!=null?"Marker error: "+session.markers.error:"B: manage markers | V: "+(session.markers.visible?"hide":"show");
        graphics.drawString(font,font.plainSubstrByWidth(m,Math.max(1,width-16)),8,72,0xffc1cad0);
        graphics.drawString(font,font.plainSubstrByWidth("S: "+structureStatus,Math.max(1,width-16)),8,83,0xffc1cad0);
        String regionHint=selectingRegion?(regionCorner==null?"Right click first corner":"Right click second corner")+" | Esc cancel":session.regions.error!=null?session.regions.error:"G: regions | H: "+(session.regions.visible?"hide":"show");
        graphics.drawString(font,font.plainSubstrByWidth(regionHint,Math.max(1,width-16)),8,94,0xffc1cad0);
    }
    void centerOn(Marker m){setCamera(new MapCamera(m.x(),m.y(),m.z(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));}
    private void drawMarkers(GuiGraphics g){
        drawnMarkers.clear();
        var snapshot=session.markers.snapshot;
        if(!session.markers.visible||snapshot==null)return;
        for(Marker m:snapshot.active()){
            var p=projection.project(m.x()+.5,m.y()+1,m.z()+.5);
            int x=(int)Math.round(width/2.0+p.x()),y=(int)Math.round(height/2.0+p.y());
            if(x<8||x>=width-8||y<113||y>=height-12)continue;
            boolean overlap=false;
            for(var prev:drawnMarkers)if(Math.abs(prev.x-x)<12&&Math.abs(prev.y-y)<12){overlap=true;break;}
            if(overlap)continue;
            if(drawnMarkers.size()>=128)break;
            drawnMarkers.add(new DrawnMarker(m,x,y));
            int color=0xff000000|m.color();
            g.fill(x-4,y-4,x+5,y+5,0xff14191d);
            g.renderOutline(x-3,y-3,7,7,color);
            String symbol=switch(m.icon()){case POINT->"+";case HOME->"H";case FLAG->"F";case MINE->"M";case PORT->"P";case FARM->"A";};
            g.drawCenteredString(font,symbol,x,y-4,color);
            if(drawnMarkers.size()<=48){
                String name=font.plainSubstrByWidth(m.name(),100);
                g.drawCenteredString(font,name,x,y+7,0xffffffff);
            }
        }
    }
    private void drawStructures(GuiGraphics g){
        if(!session.structuresVisible){structureStatus="Structures off";return;}
        // Start-chunk centers are horizontal locations only. Do not invent an elevation in relief view.
        if(camera.elevationDegrees()!=90){structureStatus="Structures: flat view only";return;}
        var bounds=StructureView.bounds(projection.bounds(width,height,camera.y(),camera.y()));
        var result=session.structureView.tick(bounds,session.io.structureRevision(session.dimension));
        structureStatus=result.limited()?"Structure view limited; zoom in":session.structureView.error()!=null?session.structureView.error():session.structureView.busy()?"Structures loading":"Structures "+result.starts().size();
        List<int[]> used=new ArrayList<>();
        for(var marker:drawnMarkers)used.add(new int[]{marker.x,marker.y});
        int displayed=0;
        for(var start:result.starts()){
            int x=(int)Math.round(width/2.0+projection.screenX(start.x(),start.z()));
            int y=(int)Math.round(height/2.0+projection.screenY(start.x(),camera.y(),start.z()));
            if(x<8||x>=width-8||y<113||y>=height-12)continue;
            boolean overlap=false;for(int[] at:used)if(Math.abs(at[0]-x)<16&&Math.abs(at[1]-y)<16){overlap=true;break;}
            if(overlap)continue;
            if(displayed>=96)break;
            used.add(new int[]{x,y});displayed++;
            var description=StructureCatalog.describe(start.type());
            drawStructureIcon(g,x,y,description.category());
            if(displayed<=32){
                String name=Component.translatableWithFallback(description.translationKey(),description.fallback()).getString();
                if(!start.exactCenter())name="~ "+name;
                g.drawCenteredString(font,font.plainSubstrByWidth(name,100),x,y+7,0xffb7e1ee);
            }
        }
    }
    // Monochrome 9x9 line pictograms; shared across styles, no texture uploads.
    private static final String[][] STRUCTURE_ICONS={
        {"....#....","...#.#...","..#...#..",".#.....#.","#########",".#.....#.",".#..#..#.",".#..#..#.",".#######."},
        {"#.#.#.#.#","#####.###","#.......#","#.......#","#...#...#","#..#.#..#","#..#.#..#","#..#.#..#","#########"},
        {"....#....","....##...","....#.#..","....#..#.","....#####","#########",".#.....#.","..#####..","........."},
        {"..#####..",".#.....#.","#.......#","#..###..#","#..#.#..#","#..#.#..#","#..#.#..#","#..#.#..#","#########"},
        {"..#####..",".#.....#.",".#.....#.",".#.....#.",".#.....#.",".#.....#.",".#.....#.",".#.....#.","#########"},
        {"#.......#","###...###","#.#...#.#","#.#####.#","#.......#","#...#...#","#...#...#","#...#...#","#########"},
        {"...###...","...#.#...","..#####..","..#...#..","..#####..","....#....","...###...","...#.#...","..#####.."},
        {"....#....","...#.#...","..#...#..",".#.....#.","#...#...#",".#.....#.","..#...#..","...#.#...","....#...."}
    };
    private static void drawStructureIcon(GuiGraphics g,int x,int y,StructureCatalog.Category category){
        g.fill(x-5,y-5,x+6,y+6,0xff20262d);
        String[] pattern=STRUCTURE_ICONS[category.ordinal()];
        for(int row=0;row<9;row++){
            String line=pattern[row];
            for(int col=0;col<9;){
                if(line.charAt(col)!='#'){col++;continue;}
                int end=col+1;while(end<9&&line.charAt(end)=='#')end++;
                g.fill(x-4+col,y-4+row,x-4+end,y-3+row,0xff8ed8ef);
                col=end;
            }
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
        if(session.regions.visible&&snapshot!=null){
            var b=projection.bounds(width,height,camera.y(),camera.y());
            var visible=snapshot.active().stream().filter(r->r.intersects(b))
                .sorted(Comparator.comparingLong(NamedRegion::area).reversed().thenComparing(NamedRegion::id)).limit(64).toList();
            for(var r:visible)drawRegionRect(g,r.minX(),r.minZ(),r.maxX(),r.maxZ(),r.color(),r.name());
        }
        if(selectingRegion&&regionCorner!=null){
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
        if(!name.isEmpty()&&px>=8&&px<width-8&&py>=113&&py<height-12&&hw>=15&&hh>=8)
            g.drawCenteredString(font,font.plainSubstrByWidth(name,120),(int)px,(int)py-4,0xff000000|color);
    }
    @Override public boolean mouseClicked(double mx,double my,int button){
        if(my<107)return true;
        if(button==0)return true;
        if(button!=1)return super.mouseClicked(mx,my,button);
        if(selectingRegion){
            if(camera.elevationDegrees()!=90||session.regions.busy()||session.regions.snapshot==null)return true;
            int[] end=blockAt(mx,my);if(regionCorner==null){regionCorner=end;return true;}
            minecraft.setScreen(new V4RegionEditor(this,session.regions,null,regionCorner[0],regionCorner[1],end[0],end[1]));selectingRegion=false;regionCorner=null;return true;
        }
        if(session.markers.snapshot==null||session.markers.busy())return true;
        for(var d:drawnMarkers)if(Math.abs(d.x-mx)<=7&&Math.abs(d.y-my)<=7){
            Marker m=d.marker;minecraft.setScreen(new V4MarkerEditor(this,session,m,m.x(),m.y(),m.z(),false));return true;
        }
        picked=session.pick(camera,mx-width/2.0,my-height/2.0);
        if(picked!=null){
            var w=picked.world();
            minecraft.setScreen(new V4MarkerEditor(this,session,null,(int)Math.floor(w.x()),(int)Math.floor(w.y()),(int)Math.floor(w.z()),picked.lod()!=0||picked.side()));
        }
        return true;
    }
    @Override public boolean mouseDragged(double mx,double my,int button,double dx,double dy){
        if(button!=0)return super.mouseDragged(mx,my,button,dx,dy);
        if(my<107)return true;
        var p=camera.atHeight(-dx,-dy,camera.y());
        setCamera(new MapCamera(p.x(),camera.y(),p.z(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));
        return true;
    }
    @Override public boolean mouseScrolled(double mx,double my,double horizontal,double vertical){
        double next=Math.max(minScale(),Math.min(16,camera.pixelsPerBlock()*Math.pow(1.25,vertical)));
        var hit=session.pick(camera,mx-width/2.0,my-height/2.0);
        var anchor=hit==null?camera.atHeight(mx-width/2.0,my-height/2.0,camera.y()):hit.world();
        setCamera(camera.zoomAt(next,anchor));return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(selectingRegion&&key==GLFW.GLFW_KEY_ESCAPE){selectingRegion=false;regionCorner=null;return true;}
        if(selectingRegion&&key==GLFW.GLFW_KEY_T)return true;
        if(key==GLFW.GLFW_KEY_G){minecraft.setScreen(new V4RegionManager(this,session.regions));return true;}
        if(key==GLFW.GLFW_KEY_H){session.regions.visible=!session.regions.visible;return true;}
        if(key==GLFW.GLFW_KEY_S){session.structuresVisible=!session.structuresVisible;return true;}
        if(key==GLFW.GLFW_KEY_B){minecraft.setScreen(new V4MarkerManager(this,session));return true;}
        if(key==GLFW.GLFW_KEY_V){session.markers.visible=!session.markers.visible;return true;}
        if(key==GLFW.GLFW_KEY_C){session.contoursEnabled=!session.contoursEnabled;return true;}
        if(key==GLFW.GLFW_KEY_M){onClose();return true;}
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_5){session.style=MapRaster.Style.values()[key-GLFW.GLFW_KEY_1];return true;}
        if(key==GLFW.GLFW_KEY_R&&minecraft!=null&&minecraft.player!=null){
            setCamera(new MapCamera(minecraft.player.getX(),minecraft.player.getY(),minecraft.player.getZ(),camera.pixelsPerBlock(),camera.yawDegrees(),camera.elevationDegrees()));return true;
        }
        if(key==GLFW.GLFW_KEY_T){
            int i=0;while(i<ANGLES.length-1&&ANGLES[i]!=camera.elevationDegrees())i++;
            setCamera(camera.withAngles(camera.yawDegrees(),ANGLES[(i+1)%ANGLES.length]));return true;
        }
        if(key==GLFW.GLFW_KEY_Q||key==GLFW.GLFW_KEY_E){setCamera(camera.withAngles(camera.yawDegrees()+(key==GLFW.GLFW_KEY_Q?-45:45),camera.elevationDegrees()));return true;}
        if(key==GLFW.GLFW_KEY_N){setCamera(camera.withAngles(0,90));return true;}
        return super.keyPressed(key,scan,modifiers);
    }
}
