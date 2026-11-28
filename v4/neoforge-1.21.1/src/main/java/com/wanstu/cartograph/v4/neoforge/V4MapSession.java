package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Bounded atlas, one terrain draw batch. All GPU operations run on the render thread. */
final class V4MapSession implements AutoCloseable {
    final WorldIoService io;
    final String dimension;
    final V4MarkerState markers;
    final V4RegionState regions;
    final StructureView structureView;
    final MapLayers layers=new MapLayers();
    final MapDisplaySettings display=new MapDisplaySettings();
    final V4IconAtlas icons=new V4IconAtlas();
    final MapPresentationClient pages;
    MapRaster.Style style=MapRaster.Style.SATELLITE_MODERN;
    MapCamera camera;
    MapPreferences preferences(){return MapPreferences.capture(style,layers,display);}
    private static final int WIDTH=1024,HEIGHT=512,EDGE=32,COUNT=512,MAX_PLANES=3;
    private final MapAtlasSlots slots=new MapAtlasSlots(COUNT);
    private record Plane(DynamicTexture texture,ResourceLocation id){}
    private final LinkedHashMap<MapRaster.Style,Plane> planes=new LinkedHashMap<>(8,.75f,true);
    private final MapWorkBudget workBudget=new MapWorkBudget();
    private boolean previousPrefetch;
    void recordWork(long nanos){workBudget.sample(nanos);}
    String pressureStatus(){return "Budget "+workBudget.pressure()+" | planes "+planes.size()+" | slots "+slots.size();}
    private List<Key> previousVisible;
    private List<Key> requested=List.of(),uploadOrder=List.of();
    private long uploads;
    private int drawCalls,exactPages,fallbackPages,quads;
    // Last uploaded observations, independent of the asynchronous CPU invalidation queue.
    // Bounded by atlas residency, never used across styles or dimensions.
    private final Map<Key,MapRaster> published=new HashMap<>();
    private List<Group> presentedGroups=List.of();
    private MapRaster.Style presentedStyle;
    private boolean presentedRaised;
    private long heldGroups;
    V4MapSession(WorldIoService io,String dimension,MapPreferences preferences,MapCamera camera){
        this.camera=camera;this.style=preferences.style();preferences.apply(layers,display);
        this.io=io;this.dimension=dimension;markers=new V4MarkerState(io,dimension);regions=new V4RegionState(io,dimension);structureView=new StructureView((b,mask)->io.structures(dimension,b,mask));
        pages=new MapPresentationClient(io.presentationChanges(dimension),k->io.raster(dimension,k),512,4);
    }
    private Plane ensureAtlas(){
        Plane plane=planes.get(style);if(plane!=null)return plane;
        while(planes.size()>=MAX_PLANES){
            var it=planes.entrySet().iterator();var old=it.next();
            slots.clearStyle(old.getKey());Minecraft.getInstance().getTextureManager().release(old.getValue().id());it.remove();
        }
        var texture=new DynamicTexture(WIDTH,HEIGHT,true);texture.setFilter(false,false);
        var id=Minecraft.getInstance().getTextureManager().register("cartograph_v4_atlas_"+style.name().toLowerCase(Locale.ROOT),texture);
        plane=new Plane(texture,id);planes.put(style,plane);return plane;
    }
    private MapAtlasSlots.Page key(MapRaster raster){return new MapAtlasSlots.Page(raster.key(),raster.generation());}
    private int abgr(int argb){return (argb&0xff00ff00)|((argb&0xff)<<16)|((argb>>>16)&0xff);}
    private MapRaster readyRaster(Key k){
        var fresh=pages.get(k);
        if(fresh!=null&&fresh.empty())return null;
        var r=published.get(k);
        return r!=null&&slots.ready(key(r),style)?r:null;
    }
    private MapTileCoverage.State state(Key k){
        var r=pages.get(k);
        if(r!=null&&r.empty())return MapTileCoverage.State.EMPTY;
        return readyRaster(k)!=null?MapTileCoverage.State.READY:MapTileCoverage.State.MISSING;
    }
    private long branchRevision=-1,branchUploads=-1;
    private MapRaster.Style branchStyle;
    private Set<Key> branchCache=Set.of();
    private Set<Key> coverageBranches(){
        long revision=pages.revision();
        if(branchRevision==revision&&branchUploads==uploads&&branchStyle==style)return branchCache;
        Set<Key> known=new HashSet<>();
        for(var e:published.entrySet())if(slots.ready(key(e.getValue()),style))known.add(e.getKey());
        pages.addEmptyKeys(known);
        branchCache=MapTileCoverage.branches(known);
        branchRevision=revision;branchUploads=uploads;branchStyle=style;
        return branchCache;
    }
    /** Parent preview first; visible exact tiles next. Neighbor ring is CPU prefetch only. */
    void prepare(List<Key> visible){
        boolean prefetch=workBudget.prefetchNeighbors();
        if(visible!=previousVisible||prefetch!=previousPrefetch){
            previousVisible=visible;previousPrefetch=prefetch;
            requested=MapTileCoverage.requests(visible,Math.min(480,visible.size()+64),prefetch);
            Set<Key> urgent=new HashSet<>(visible);
            for(Key k:visible){Key p=MapTileCoverage.parent(k);if(p!=null)urgent.add(p);}
            uploadOrder=requested.stream().filter(urgent::contains).toList();
        }
        pages.tick(requested);
        published.entrySet().removeIf(e->{
            var fresh=pages.get(e.getKey());
            if(fresh!=null&&fresh.empty()){slots.discard(e.getKey());return true;}
            return slots.slot(key(e.getValue()))==null;
        });
        boolean raised=camera!=null&&camera.elevationDegrees()!=90;
        boolean samePresentation=presentedStyle==style&&presentedRaised==raised;
        Set<MapAtlasSlots.Page> protectedKeys=new HashSet<>(),previewKeys=new LinkedHashSet<>();
        var branches=coverageBranches();
        for(Key k:visible){
            var r=readyRaster(k);
            if(r!=null){var tk=key(r);protectedKeys.add(tk);slots.touch(tk);}
            // Retain existing parent previews unless exact coverage is already uploaded.
            if(state(k)==MapTileCoverage.State.MISSING){
                for(var patch:MapTileCoverage.resolve(k,this::state,branches)){
                    var source=readyRaster(patch.source());
                    if(source!=null){var tk=key(source);previewKeys.add(tk);slots.touch(tk);}
                }
            }
        }
        // Leave free eviction candidates even when many finer previews occupy the atlas.
        for(var tk:previewKeys){if(protectedKeys.size()>=448)break;protectedKeys.add(tk);}
        // Retain the previous complete geometry while a replacement is staged.
        // Old off-screen pages remain ordinary LRU candidates when capacity is tight.
        if(samePresentation)for(var group:presentedGroups)for(var part:group.parts){
            if(protectedKeys.size()>=448)break;
            var k=key(part.raster);if(slots.ready(k,style))protectedKeys.add(k);
        }
        int budget=workBudget.textureUploads();
        long started=System.nanoTime();
        for(Key k:uploadOrder){
            if(budget==0||(budget<workBudget.textureUploads()&&System.nanoTime()-started>workBudget.sliceNanos()))break;
            var raster=pages.get(k);
            // Every observed raster contains all five palettes. Recolor the last
            // resident observation immediately if CPU refresh is still pending.
            if(raster==null)raster=published.get(k);
            if(raster==null||raster.empty()||pages.get(k)==null&&slots.slot(key(raster))==null)continue;
            var key=key(raster);
            if(slots.ready(key,style)){published.put(k,raster);continue;}
            var old=published.get(k);
            // Flat quads keep their UV/address; replacing pixels is atomic on the
            // render thread. Relief needs old pixels until its new VBO is drawable.
            if(!raised&&old!=null)protectedKeys.remove(key(old));
            Integer slot=raised?slots.reserveStaged(key,protectedKeys):slots.reserve(key,protectedKeys);
            if(slot==null)continue;
            var texture=ensureAtlas().texture();
            int ox=slot%32*EDGE,oy=slot/32*EDGE;
            var image=texture.getPixels();
            for(int i=0;i<1024;i++)image.setPixelRGBA(ox+i%EDGE,oy+i/EDGE,abgr(raster.argb(style,i)));
            texture.bind();image.upload(0,ox,oy,ox,oy,EDGE,EDGE,false,false);
            slots.uploaded(key,slot,style);published.put(k,raster);protectedKeys.add(key);budget--;uploads++;
        }
    }
    private record Part(Key target,MapTileCoverage.Patch patch,MapRaster raster,int slot){}
    @FunctionalInterface private interface Faces{
        void face(Part part,double x0,double y0,double z0,double x1,double y1,double z1,
                  double u0,double v0,double u1,double v1,int light);
    }
    private record Group(int lod,int x,int z,boolean raised,List<Part> parts,MapGeometryKey geometry){}
    private record Geometry(VertexBuffer buffer,int quads) implements AutoCloseable{
        public void close(){if(buffer!=null)buffer.close();}
    }
    private final RetainedGroups<MapGeometryKey,Geometry> meshes=new RetainedGroups<>(32L*1024*1024,128);
    private List<Group> wantedGroups=List.of();
    private Set<MapGeometryKey> protectedGroups=Set.of();
    private List<Part> drawnParts=List.of();
    private Map<Key,List<Part>> anchorParts=Map.of();
    private List<Part> anchorDependencies=List.of();
    private List<Part> retained=List.of();
    private List<Key> lastDrawKeys;
    private long lastRevision=-1,lastUploads=-1;
    private boolean lastRaised;
    private MapRaster.Style lastStyle;
    private MapCamera lastRenderedCamera;
    private static final int MAX_QUADS=262144;

    /** Compare actual drawable dependencies, not camera position or unrelated cache arrivals. */
    private void refresh(List<Key> visible,boolean raised){
        long rev=pages.revision();
        if(lastDrawKeys==visible&&lastRevision==rev&&lastUploads==uploads&&lastRaised==raised&&lastStyle==style)return;
        boolean modeChanged=lastRaised!=raised;
        lastDrawKeys=visible;lastRevision=rev;lastUploads=uploads;lastRaised=raised;lastStyle=style;
        List<Key> ordered=new ArrayList<>(visible);
        ordered.sort(Comparator.comparingInt(Key::lod).thenComparingInt(Key::x).thenComparingInt(Key::z));
        List<Part> next=new ArrayList<>();
        int exact=0,fallback=0,upper=0;
        var branches=coverageBranches();
        for(Key k:ordered){
            var patches=MapTileCoverage.resolve(k,this::state,branches);
            boolean isExact=patches.size()==1&&patches.getFirst().source().equals(k);
            if(isExact)exact++;else if(!patches.isEmpty())fallback++;
            for(var patch:patches){
                var raster=readyRaster(patch.source());if(raster==null)continue;
                Integer slot=slots.slot(key(raster));if(slot==null)continue;
                next.add(new Part(k,patch,raster,slot));
                upper+=raised?raster.relief().topCount()+raster.relief().wallCount():1;
            }
        }
        // Exact raised pages fit even at the 64-page worst case. Bound unusually dense
        // transient child previews; they are replaced by exact pages when available.
        if(upper>MAX_QUADS&&raised){
            for(var it=next.listIterator(next.size());it.hasPrevious()&&upper>MAX_QUADS;){
                Part p=it.previous();
                if(p.patch.source().equals(p.target))continue;
                upper-=p.raster.relief().topCount()+p.raster.relief().wallCount();it.remove();
            }
        }
        // Cached geometry keys contain no raster references. Old immutable vertex buffers
        // are bounded by the existing LRU; only exact current drawable keys can be drawn.
        if(!modeChanged&&retained.equals(next))return;
        retained=List.copyOf(next);exactPages=exact;
        fallbackPages=(int)next.stream().filter(p->!p.patch.source().equals(p.target)).map(Part::target).distinct().count();
        Map<String,List<Part>> grouped=new LinkedHashMap<>();
        for(Part p:retained){Key k=p.target;int edge=raised?2:8;
            String id=k.lod()+":"+Math.floorDiv(k.x(),edge)+":"+Math.floorDiv(k.z(),edge);
            grouped.computeIfAbsent(id,ignored->new ArrayList<>()).add(p);}
        List<Group> groups=new ArrayList<>();
        for(var parts:grouped.values()){Key k=parts.getFirst().target;int edge=raised?2:8;
            int gx=Math.floorDiv(k.x(),edge),gz=Math.floorDiv(k.z(),edge);
            var geometry=new MapGeometryKey(k.lod(),gx,gz,raised,parts.stream().map(p->new MapGeometryKey.Face(p.target,p.patch,p.slot,p.raster.generation())).toList());
            groups.add(new Group(k.lod(),gx,gz,raised,List.copyOf(parts),geometry));}
        Map<Key,Integer> rank=new HashMap<>();for(int i=0;i<visible.size();i++)rank.put(visible.get(i),i);
        groups.sort(Comparator.comparingInt(g->g.parts.stream().mapToInt(p->rank.get(p.target)).min().orElse(Integer.MAX_VALUE)));
        wantedGroups=List.copyOf(groups);protectedGroups=Set.copyOf(groups.stream().map(Group::geometry).toList());
    }
    private record GroupAddress(int lod,int x,int z,boolean raised){}
    private static GroupAddress address(Group g){return new GroupAddress(g.lod,g.x,g.z,g.raised);}
    /** Validate every old dependency. Flat in-place pixel updates can reuse the old
     * geometry with the new matching raster, but never an evicted/reassigned slot. */
    private Group stillDrawable(Group g){
        var parts=new ArrayList<Part>(g.parts.size());
        for(var p:g.parts){
            var current=pages.get(p.patch.source());
            if(current!=null&&current.empty())return null;
            var raster=p.raster;
            if(!g.raised){
                raster=readyRaster(p.patch.source());
                if(raster==null)return null;
            }
            var k=key(raster);
            if(!slots.ready(k,style)||!Objects.equals(slots.slot(k),p.slot))return null;
            parts.add(new Part(p.target,p.patch,raster,p.slot));
        }
        if(meshes.get(g.geometry)==null)return null;
        return new Group(g.lod,g.x,g.z,g.raised,List.copyOf(parts),g.geometry);
    }
    private static long estimate(Group group){
        long faces=0;
        for(Part p:group.parts)faces+=group.raised?p.raster.relief().topCount()+p.raster.relief().wallCount():1;
        return faces*96;
    }
    private static double originX(Group group){return group.x*32.0*(1<<group.lod)*(group.raised?2:8);}
    private static double originZ(Group group){return group.z*32.0*(1<<group.lod)*(group.raised?2:8);}
    private RetainedGroups.Item<Geometry> build(Group group){
        double ox=originX(group),oz=originZ(group);
        int[] count={0};
        var buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
        emit(group.parts,group.raised,null,(part,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->{
            float u=(part.slot%32*EDGE)/(float)WIDTH,v=(part.slot/32*EDGE)/(float)HEIGHT;
            float a=u+(float)(u0/WIDTH),b=v+(float)(v0/HEIGHT),c=u+(float)(u1/WIDTH),d=v+(float)(v1/HEIGHT);
            if(y0==y1){
                vertex(buffer,x0-ox,y0,z0-oz,a,b,light);
                vertex(buffer,x0-ox,y0,z1-oz,a,d,light);
                vertex(buffer,x1-ox,y0,z1-oz,c,d,light);
                vertex(buffer,x1-ox,y0,z0-oz,c,b,light);
            }else{
                vertex(buffer,x0-ox,y0,z0-oz,a,b,light);
                vertex(buffer,x0-ox,y1,z0-oz,a,b,light);
                vertex(buffer,x1-ox,y1,z1-oz,a,b,light);
                vertex(buffer,x1-ox,y0,z1-oz,a,b,light);
            }
            count[0]++;
        });
        var built=buffer.build();
        if(built==null)return new RetainedGroups.Item<>(new Geometry(null,0),0);
        var terrain=new VertexBuffer(VertexBuffer.Usage.STATIC);
        terrain.bind();
        try{terrain.upload(built);}
        catch(RuntimeException|Error ex){terrain.close();throw ex;}
        finally{VertexBuffer.unbind();}
        return new RetainedGroups.Item<>(new Geometry(terrain,count[0]),96L*count[0]);
    }
    private static void vertex(BufferBuilder b,double x,double y,double z,float u,float v,int light){
        b.addVertex((float)x,(float)y,(float)z).setUv(u,v).setColor(light,light,light,255);
    }
    /** One face traversal, shared between retained geometry construction and precise CPU picking. */
    private void emit(List<Part> parts,boolean raised,MapRayPicker picker,Faces sink){
        emit(parts,raised,picker,sink,Integer.MAX_VALUE);
    }
    /** Work cap counts traversed parts and all potential mesh/edge visits, not just emitted faces. */
    private boolean emit(List<Part> parts,boolean raised,MapRayPicker picker,Faces sink,int workBudget){
        for(Part part:parts){
            if(--workBudget<0)return false;
            var patch=part.patch;var raster=part.raster;
            double cell=1<<patch.source().lod();
            double bx=patch.source().x()*32.0*cell,bz=patch.source().z()*32.0*cell;
            double x0=patch.u()*32,z0=patch.v()*32,x1=x0+patch.uvSize()*32,z1=z0+patch.uvSize()*32;
            if(picker!=null){
                double min=raised?raster.minSurface():0,max=raised?raster.maxSurface():0;
                if(!picker.mayHit(bx+x0*cell,bz+z0*cell,bx+x1*cell,bz+z1*cell,min,max))continue;
            }
            if(!raised){
                top(sink,part,bx,bz,cell,x0,z0,x1,z1,0);continue;
            }
            var mesh=raster.relief();
            workBudget-=mesh.topCount()+mesh.wallCount();
            if(workBudget<0)return false;
            for(int i=0;i<mesh.topCount();i++){
                double left=Math.max(x0,mesh.topX(i)),right=Math.min(x1,mesh.topX(i)+mesh.topWidth(i));
                double near=Math.max(z0,mesh.topZ(i)),far=Math.min(z1,mesh.topZ(i)+mesh.topDepth(i));
                if(left<right&&near<far)top(sink,part,bx,bz,cell,left,near,right,far,mesh.topHeight(i));
            }
            for(int i=0;i<mesh.wallCount();i++){
                int x=mesh.wallX(i),z=mesh.wallZ(i),dir=mesh.wallDirection(i);
                var seg=ReliefMesh.clipInteriorWall(x,z,dir,x0,z0,x1,z1);
                if(seg!=null)
                    wall(sink,part,bx,bz,cell,x,z,dir,mesh.wallTop(i),mesh.wallBottom(i),seg.x(),seg.z(),seg.length());
            }

        }
        return true;
    }
    private static void top(Faces sink,Part p,double bx,double bz,double cell,double x0,double z0,double x1,double z1,double y){
        sink.face(p,bx+x0*cell,y,bz+z0*cell,bx+x1*cell,y,bz+z1*cell,x0,z0,x1,z1,255);
    }
    private static void wall(Faces sink,Part p,double bx,double bz,double cell,int x,int z,int dir,float top,float bottom,double edgeX,double edgeZ,double length){
        double ax=bx+edgeX*cell,az=bz+edgeZ*cell;
        sink.face(p,ax,top,az,ax+(dir>=2?cell*length:0),bottom,az+(dir<2?cell*length:0),
            x+.5,z+.5,x+.5,z+.5,dir==0||dir==2?172:211);
    }
    private List<Part> seamParts=List.of();
    private List<ReliefSeams.Wall> seamWalls=List.of();
    private VertexBuffer seamBuffer;
    private double seamOriginX,seamOriginZ;
    private long seamUploads;
    private boolean seamLimited;
    private void clearSeams(){
        if(seamBuffer!=null){seamBuffer.close();seamBuffer=null;}
        seamParts=List.of();seamWalls=List.of();seamLimited=false;
    }
    private void seamFaces(Faces sink){
        for(var w:seamWalls){
            var p=seamParts.get(w.owner());double u=w.slot()%32+.5,v=w.slot()/32+.5;
            sink.face(p,w.x(),w.top(),w.z(),w.x()+(w.direction()>=2?w.length():0),w.bottom(),
                w.z()+(w.direction()<2?w.length():0),u,v,u,v,w.direction()==0||w.direction()==2?172:211);
        }
    }
    private void drawSeams(GuiGraphics graphics,MapCamera camera,int width,int height,double depthScale){
        if(camera.elevationDegrees()==90){clearSeams();return;}
        if(!seamParts.equals(drawnParts)){
            clearSeams();seamParts=drawnParts;
            if(seamParts.isEmpty())return;
            var result=ReliefSeams.plan(seamParts.stream().map(p->new ReliefSeams.Input(p.target,p.patch,p.raster)).toList(),ReliefSeams.MAX_WALLS);
            seamWalls=result.walls();seamLimited=result.limited();
            if(!seamWalls.isEmpty()){
                var k=seamParts.getFirst().target;seamOriginX=k.x()*32.0*(1<<k.lod());seamOriginZ=k.z()*32.0*(1<<k.lod());
                var builder=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
                seamFaces((part,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->{
                    float u=(float)((part.slot%32*EDGE+u0)/WIDTH),v=(float)((part.slot/32*EDGE+v0)/HEIGHT);
                    vertex(builder,x0-seamOriginX,y0,z0-seamOriginZ,u,v,light);
                    vertex(builder,x0-seamOriginX,y1,z0-seamOriginZ,u,v,light);
                    vertex(builder,x1-seamOriginX,y1,z1-seamOriginZ,u,v,light);
                    vertex(builder,x1-seamOriginX,y0,z1-seamOriginZ,u,v,light);
                });
                var data=builder.build();
                if(data!=null){
                    var next=new VertexBuffer(VertexBuffer.Usage.STATIC);next.bind();
                    try{next.upload(data);seamBuffer=next;seamUploads++;}
                    catch(RuntimeException|Error ex){next.close();throw ex;}
                    finally{VertexBuffer.unbind();}
                }
            }
        }
        if(seamBuffer==null)return;
        double[] m=MapTransform.coefficients(camera,seamOriginX,0,seamOriginZ,width,height,depthScale,true);
        var affine=new org.joml.Matrix4f().set(
            (float)m[0],(float)m[4],(float)m[8],0,
            (float)m[1],(float)m[5],(float)m[9],0,
            (float)m[2],(float)m[6],(float)m[10],0,
            (float)m[3],(float)m[7],(float)m[11],1);
        var matrix=new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose()).mul(affine);
        seamBuffer.bind();
        try{seamBuffer.drawWithShader(matrix,RenderSystem.getProjectionMatrix(),GameRenderer.getPositionTexColorShader());drawCalls++;quads+=seamWalls.size();}
        finally{VertexBuffer.unbind();}
    }
    String seamStatus(){return "Seams "+seamWalls.size()+" | VBO "+seamUploads+(seamLimited?" | limited":"");}
    MapRayPicker.Hit pick(MapCamera camera,double sx,double sy){
        if(!camera.equals(lastRenderedCamera)||lastRaised!=(camera.elevationDegrees()!=90))return null;
        var picker=new MapRayPicker(camera,sx,sy);
        emit(drawnParts,lastRaised,picker,(part,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->picker.face(part.raster,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1));
        if(lastRaised&&seamBuffer!=null)seamFaces((p,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->picker.face(p.raster,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1));
        return picker.result();
    }
    private boolean contourAnchorVisible(MapCamera camera,ContourLabelVisibility.Anchor anchor){
        if(!camera.equals(lastRenderedCamera))return false;
        var screen=camera.project(new MapCamera.World(anchor.x(),anchor.y(),anchor.z()));
        var picker=new MapRayPicker(camera,screen.x(),screen.y(),screen.depth()+.0001);
        int remaining=8192-seamWalls.size();if(remaining<=0)return false;
        if(seamBuffer!=null)seamFaces((p,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->picker.face(p.raster,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1));
        boolean complete=emit(drawnParts,true,picker,(part,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->
            picker.face(part.raster,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1),remaining);
        return complete&&picker.result()==null;
    }
    /** Static world-relative vertices. Each camera movement changes just the affine transform. */
    void draw(GuiGraphics graphics,List<Key> visible,MapCamera camera,MapProjection projection,int width,int height,MapHudLayout hud){
        drawCalls=quads=0;lastRenderedCamera=null;drawnParts=List.of();
        Plane plane=planes.get(style);
        if(plane==null){presentedGroups=List.of();clearAnchors();clearSeams();return;}
        boolean raised=camera.elevationDegrees()!=90;
        if(presentedStyle!=style||presentedRaised!=raised)presentedGroups=List.of();
        presentedStyle=style;presentedRaised=raised;
        graphics.flush();refresh(visible,raised);
        var previous=new LinkedHashMap<GroupAddress,Group>();
        for(Group old:presentedGroups){
            var valid=stillDrawable(old);if(valid!=null)previous.put(address(valid),valid);
        }
        var protectedMeshes=new HashSet<>(protectedGroups);
        for(var old:previous.values())protectedMeshes.add(old.geometry);
        long start=System.nanoTime();int budget=workBudget.meshUploads();
        boolean limited=false;
        for(Group group:wantedGroups){
            if(meshes.get(group.geometry)!=null)continue;
            if(budget==0||(budget<workBudget.meshUploads()&&System.nanoTime()-start>=workBudget.sliceNanos()))break;
            if(meshes.ensure(group.geometry,estimate(group),protectedMeshes,ignored->build(group)))budget--;
            else {limited=true;break;}
        }
        // A same-address replacement becomes visible only once its buffer is ready.
        // When changing LOD keep the previous coherent plan until the new plan is ready;
        // do not draw overlapping old/new LOD surfaces (z fighting).
        boolean transition=!previous.isEmpty()&&!wantedGroups.isEmpty()&&
            previous.values().iterator().next().lod!=wantedGroups.getFirst().lod;
        boolean ready=wantedGroups.stream().allMatch(g->meshes.get(g.geometry)!=null);
        List<Group> renderGroups=new ArrayList<>();
        boolean waitingForCoverage=wantedGroups.isEmpty()&&!previous.isEmpty()&&visible.stream().anyMatch(k->state(k)!=MapTileCoverage.State.EMPTY);
        if(waitingForCoverage||transition&&!ready&&!limited){
            renderGroups.addAll(previous.values());heldGroups+=renderGroups.size();
        }else{
            for(Group group:wantedGroups){
                if(meshes.get(group.geometry)!=null)renderGroups.add(group);
                else{
                    var old=previous.get(address(group));
                    if(old!=null){renderGroups.add(old);heldGroups++;}
                }
            }
        }
        // If a pathological transition exceeds the existing bounded GPU budget,
        // release the old plan so the next frame can make forward progress.
        double depthScale=Math.min(1,70.0/(Math.hypot(width,height)/camera.pixelsPerBlock()*2+8192));
        RenderSystem.setShaderTexture(0,plane.id());
        RenderSystem.setShaderColor(1,1,1,1);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        if(raised){RenderSystem.depthMask(true);RenderSystem.clear(256,Minecraft.ON_OSX);RenderSystem.enableDepthTest();RenderSystem.disableCull();}
        var rendered=new ArrayList<Part>();
        var drawnGroups=new ArrayList<Group>();
        try{
            for(Group group:renderGroups){
                Geometry mesh=meshes.get(group.geometry);
                if(mesh==null||mesh.buffer==null)continue;
                double[] m=MapTransform.coefficients(camera,originX(group),0,originZ(group),width,height,depthScale,raised);
                var affine=new org.joml.Matrix4f().set(
                    (float)m[0],(float)m[4],(float)m[8],0,
                    (float)m[1],(float)m[5],(float)m[9],0,
                    (float)m[2],(float)m[6],(float)m[10],0,
                    (float)m[3],(float)m[7],(float)m[11],1);
                var matrix=new org.joml.Matrix4f(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose()).mul(affine);
                mesh.buffer.bind();
                mesh.buffer.drawWithShader(matrix,RenderSystem.getProjectionMatrix(),GameRenderer.getPositionTexColorShader());
                drawCalls++;quads+=mesh.quads;rendered.addAll(group.parts);drawnGroups.add(group);
                for(var part:group.parts)slots.touch(key(part.raster));
            }
            presentedGroups=List.copyOf(drawnGroups);
            drawnParts=List.copyOf(rendered);lastRenderedCamera=camera;
            exactPages=(int)drawnParts.stream().filter(p->p.target.equals(p.patch.source())).map(Part::target).distinct().count();
            fallbackPages=(int)drawnParts.stream().filter(p->!p.target.equals(p.patch.source())).map(Part::target).distinct().count();
            if(raised&&layers.structuresActive()){
                if(!anchorDependencies.equals(drawnParts)){
                    var index=new HashMap<Key,List<Part>>();
                    for(var part:drawnParts)index.computeIfAbsent(part.target,k->new ArrayList<>(4)).add(part);
                    anchorParts=index;anchorDependencies=drawnParts;
                }
            }else clearAnchors();
            drawSeams(graphics,camera,width,height,depthScale);
            drawShorelines(graphics,camera,width,height);
            drawContours(graphics,Minecraft.getInstance().font,visible,camera,projection,width,height,depthScale);
        }finally{
            VertexBuffer.unbind();
            if(raised){RenderSystem.clear(256,Minecraft.ON_OSX);RenderSystem.enableCull();RenderSystem.enableDepthTest();}
            RenderSystem.disableBlend();
        }
    }
    void drawContourLabels(GuiGraphics graphics,MapCamera camera,MapProjection projection,int width,int height,MapHudLayout hud,MapLabelLayout labels){
        if(lastRenderedCamera!=null&&layers.visible(MapLayers.Layer.CONTOURS)&&contours!=null)
            contours.drawLabels(graphics,Minecraft.getInstance().font,camera,projection,width,height,labels,display.labels(),drawnParts,
                anchor->contourAnchorVisible(camera,anchor));
    }
    private void clearAnchors(){anchorParts=Map.of();anchorDependencies=List.of();}
    SurfaceAnchor.Sample structureHeight(int lod,int x,int z){
        var target=SurfaceAnchor.keyAt(lod,x,z);
        var parts=anchorParts.get(target);
        if(parts==null)return null;
        for(var part:parts){
            var sample=SurfaceAnchor.sample(target,part.patch,part.raster,x,z);
            if(sample!=null)return sample;
        }
        return null;
    }
    long heldGroups(){return heldGroups;}
    long meshUploads(){return meshes.builds();}
    long meshBytes(){return meshes.bytes();}
    private V4ContourLayer contours;
    private V4ShorelineLayer shores;
    private V4LandformLayer landforms;
    private List<MapLandforms> landformPages=List.of();
    private List<Part> shoreDependencies=List.of();
    private List<MapShorelines> shorePages=List.of();
    private void drawShorelines(GuiGraphics graphics,MapCamera camera,int width,int height){
        if(camera.elevationDegrees()!=90||!V4ShorelineLayer.supported(style)){
            if(shores!=null){shores.close();shores=null;}
            if(landforms!=null){landforms.close();landforms=null;}
            shoreDependencies=List.of();shorePages=List.of();landformPages=List.of();return;
        }
        if(!shoreDependencies.equals(drawnParts)){
            shoreDependencies=drawnParts;
            shorePages=drawnParts.stream().filter(p->p.target.equals(p.patch.source()))
                .map(p->p.raster.shorelines()).sorted(Comparator.comparingInt((MapShorelines p)->p.key().lod())
                    .thenComparingInt(p->p.key().x()).thenComparingInt(p->p.key().z())).toList();
            landformPages=drawnParts.stream().filter(p->p.target.equals(p.patch.source()))
                .map(p->p.raster.landforms()).filter(p->!p.symbols().isEmpty())
                .sorted(Comparator.comparingInt((MapLandforms p)->p.key().lod()).thenComparingInt(p->p.key().x()).thenComparingInt(p->p.key().z())).toList();
        }
        if(shores==null)shores=new V4ShorelineLayer();
        shores.draw(graphics,shorePages,style,camera,width,height);
        if(landforms==null)landforms=new V4LandformLayer();
        landforms.draw(graphics,landformPages,style,camera,width,height);
    }
    String shoreStatus(){return shores==null?"Shore off":shores.status()+(landforms==null?"":" | "+landforms.status());}
    void drawContours(GuiGraphics graphics,net.minecraft.client.gui.Font font,List<Key> visible,MapCamera camera,MapProjection projection,int width,int height,double depthScale){
        if(!layers.visible(MapLayers.Layer.CONTOURS))return;
        if(contours==null)contours=new V4ContourLayer();
        Map<Key,MapRaster> exact=new LinkedHashMap<>();
        for(var part:drawnParts)if(part.target.equals(part.patch.source()))exact.put(part.target,part.raster);
        List<ContourOverlayCache.Input> inputs=new ArrayList<>();
        for(var entry:exact.entrySet()){
            Key k=entry.getKey();
            inputs.add(new ContourOverlayCache.Input(entry.getValue(),
                contourNeighbor(exact,k,1,0),contourNeighbor(exact,k,0,1),contourNeighbor(exact,k,1,1)));
        }
        contours.draw(graphics,font,inputs,camera,projection,width,height,depthScale);
    }
    private MapRaster contourNeighbor(Map<Key,MapRaster> exact,Key k,int dx,int dz){
        Key neighbor=new Key(k.lod(),k.x()+dx,k.z()+dz);return exact.get(neighbor);
    }
    String contourStatus(){
        if(!layers.visible(MapLayers.Layer.CONTOURS))return "Contours off";
        if(contours==null)return "Contours loading";
        return "Contours "+contours.segments()+" | VBO "+contours.uploads()+(contours.limited()?" | budget limited":"");
    }
    int drawCalls(){return drawCalls;}
    int exactPages(){return exactPages;}
    int fallbackPages(){return fallbackPages;}
    int quads(){return quads;}
    long uploads(){return uploads;}
    @Override public void close(){
        icons.close();clearSeams();
        if(contours!=null){contours.close();contours=null;}
        if(shores!=null){shores.close();shores=null;}
        if(landforms!=null){landforms.close();landforms=null;}
        shoreDependencies=List.of();shorePages=List.of();landformPages=List.of();
        meshes.close();retained=List.of();wantedGroups=List.of();protectedGroups=Set.of();drawnParts=List.of();clearAnchors();
        for(var plane:planes.values())Minecraft.getInstance().getTextureManager().release(plane.id());
        planes.clear();slots.clear();published.clear();presentedGroups=List.of();
        pages.close();branchCache=Set.of();requested=List.of();uploadOrder=List.of();previousVisible=null;lastDrawKeys=null;lastRenderedCamera=null;
    }
}
