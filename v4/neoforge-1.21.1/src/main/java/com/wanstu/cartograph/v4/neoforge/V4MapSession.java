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
    final MapPresentationClient pages;
    MapRaster.Style style=MapRaster.Style.SATELLITE_MODERN;
    double yaw=0,elevation=90;
    private static final int SIZE=1024,EDGE=32,COUNT=512;
    private record TextureKey(Key key,MapRaster.Style style,long generation){}
    private final LinkedHashMap<TextureKey,Integer> slots=new LinkedHashMap<>(16,.75f,true);
    private final ArrayDeque<Integer> free=new ArrayDeque<>();
    private DynamicTexture texture;
    private ResourceLocation textureId;
    private List<Key> previousVisible;
    private List<Key> requested=List.of(),uploadOrder=List.of();
    private long uploads;
    private int drawCalls,exactPages,fallbackPages,quads;
    V4MapSession(WorldIoService io,String dimension){
        this.io=io;this.dimension=dimension;markers=new V4MarkerState(io,dimension);regions=new V4RegionState(io,dimension);structureView=new StructureView((b,mask)->io.structures(dimension,b,mask));
        pages=new MapPresentationClient(io.presentationChanges(dimension),k->io.raster(dimension,k),512,4);
        for(int i=0;i<COUNT;i++)free.add(i);
    }
    private void ensureAtlas(){
        if(texture!=null)return;
        texture=new DynamicTexture(SIZE,SIZE,true);
        texture.setFilter(false,false);
        textureId=Minecraft.getInstance().getTextureManager().register("cartograph_v4_atlas",texture);
    }
    private TextureKey key(MapRaster raster){return new TextureKey(raster.key(),style,raster.generation());}
    private int abgr(int argb){return (argb&0xff00ff00)|((argb&0xff)<<16)|((argb>>>16)&0xff);}
    private MapTileCoverage.State state(Key k){
        var r=pages.get(k);
        if(r==null)return MapTileCoverage.State.MISSING;
        if(r.empty())return MapTileCoverage.State.EMPTY;
        return slots.containsKey(key(r))?MapTileCoverage.State.READY:MapTileCoverage.State.MISSING;
    }
    /** Parent preview first; visible exact tiles next. Neighbor ring is CPU prefetch only. */
    void prepare(List<Key> visible){
        if(visible!=previousVisible){
            previousVisible=visible;
            requested=MapTileCoverage.requests(visible,Math.min(480,visible.size()+64));
            Set<Key> urgent=new HashSet<>(visible);
            for(Key k:visible){Key p=MapTileCoverage.parent(k);if(p!=null)urgent.add(p);}
            uploadOrder=requested.stream().filter(urgent::contains).toList();
        }
        pages.tick(requested);
        Set<TextureKey> protectedKeys=new HashSet<>(),previewKeys=new LinkedHashSet<>();
        for(Key k:visible){
            var r=pages.get(k);
            if(r!=null&&!r.empty()){var tk=key(r);protectedKeys.add(tk);slots.get(tk);}
            // Retain existing parent previews unless exact coverage is already uploaded.
            if(state(k)==MapTileCoverage.State.MISSING){
                for(var patch:MapTileCoverage.resolve(k,this::state)){
                    var source=pages.get(patch.source());
                    if(source!=null){var tk=key(source);previewKeys.add(tk);slots.get(tk);}
                }
            }
        }
        // Leave free eviction candidates even when many finer previews occupy the atlas.
        for(var tk:previewKeys){if(protectedKeys.size()>=448)break;protectedKeys.add(tk);}
        int budget=4;
        long started=System.nanoTime();
        for(Key k:uploadOrder){
            if(budget==0||System.nanoTime()-started>1_000_000)break;
            var raster=pages.get(k);if(raster==null||raster.empty())continue;
            var key=key(raster);if(slots.containsKey(key))continue;
            ensureAtlas();
            Integer slot=free.pollFirst();
            if(slot==null){
                var it=slots.entrySet().iterator();
                while(it.hasNext()){
                    var e=it.next();
                    if(!protectedKeys.contains(e.getKey())){slot=e.getValue();it.remove();break;}
                }
            }
            if(slot==null)break;
            int ox=slot%32*EDGE,oy=slot/32*EDGE;
            var image=texture.getPixels();
            for(int i=0;i<1024;i++)image.setPixelRGBA(ox+i%EDGE,oy+i/EDGE,abgr(raster.argb(style,i)));
            texture.bind();image.upload(0,ox,oy,ox,oy,EDGE,EDGE,false,false);
            slots.put(key,slot);budget--;uploads++;
        }
    }
    private record Part(Key target,MapTileCoverage.Patch patch,MapRaster raster,int slot,List<MapRaster> adjacent){}
    @FunctionalInterface private interface Faces{
        void face(Part part,double x0,double y0,double z0,double x1,double y1,double z1,
                  double u0,double v0,double u1,double v1,int light);
    }
    private record Group(int lod,int x,int z,boolean raised,List<Part> parts){}
    private record Geometry(VertexBuffer buffer,int quads) implements AutoCloseable{
        public void close(){if(buffer!=null)buffer.close();}
    }
    private final RetainedGroups<Group,Geometry> meshes=new RetainedGroups<>(32L*1024*1024,128);
    private List<Group> wantedGroups=List.of();
    private Set<Group> protectedGroups=Set.of();
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
        for(Key k:ordered){
            var patches=MapTileCoverage.resolve(k,this::state);
            boolean isExact=patches.size()==1&&patches.getFirst().source().equals(k);
            if(isExact)exact++;else if(!patches.isEmpty())fallback++;
            for(var patch:patches){
                var raster=pages.get(patch.source());if(raster==null)continue;
                Integer slot=slots.get(key(raster));if(slot==null)continue;
                List<MapRaster> neighbors=List.of();
                if(raised&&isExact){
                    MapRaster[] adjacent=new MapRaster[4];
                    for(int dir=0;dir<4;dir++){
                        Key other=new Key(k.lod(),k.x()+(dir==0?-1:dir==1?1:0),k.z()+(dir==2?-1:dir==3?1:0));
                        if(state(other)==MapTileCoverage.State.READY)adjacent[dir]=pages.get(other);
                    }
                    neighbors=Arrays.asList(adjacent);
                }
                next.add(new Part(k,patch,raster,slot,neighbors));
                upper+=raised?raster.relief().topCount()+raster.relief().wallCount()+(isExact?128:0):1;
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
        // Drop stale invisible groups too, so their raster/neighbor references cannot accumulate.
        meshes.retainValid(g->g.parts.stream().allMatch(p->Objects.equals(slots.get(key(p.raster)),p.slot)&&pages.get(p.raster.key())==p.raster&&p.adjacent.stream().allMatch(n->n==null||pages.get(n.key())==n)));
        if(!modeChanged&&retained.equals(next))return;
        retained=List.copyOf(next);exactPages=exact;
        fallbackPages=(int)next.stream().filter(p->!p.patch.source().equals(p.target)).map(Part::target).distinct().count();
        Map<String,List<Part>> grouped=new LinkedHashMap<>();
        for(Part p:retained){Key k=p.target;int edge=raised?2:8;
            String id=k.lod()+":"+Math.floorDiv(k.x(),edge)+":"+Math.floorDiv(k.z(),edge);
            grouped.computeIfAbsent(id,ignored->new ArrayList<>()).add(p);}
        List<Group> groups=new ArrayList<>();
        for(var parts:grouped.values()){Key k=parts.getFirst().target;int edge=raised?2:8;
            groups.add(new Group(k.lod(),Math.floorDiv(k.x(),edge),Math.floorDiv(k.z(),edge),raised,List.copyOf(parts)));}
        Map<Key,Integer> rank=new HashMap<>();for(int i=0;i<visible.size();i++)rank.put(visible.get(i),i);
        groups.sort(Comparator.comparingInt(g->g.parts.stream().mapToInt(p->rank.get(p.target)).min().orElse(Integer.MAX_VALUE)));
        wantedGroups=List.copyOf(groups);protectedGroups=Set.copyOf(groups);
    }
    private static long estimate(Group group){
        long faces=0;
        for(Part p:group.parts)faces+=group.raised?p.raster.relief().topCount()+p.raster.relief().wallCount()+128:1;
        return faces*96;
    }
    private static double originX(Group group){return group.x*32.0*(1<<group.lod)*(group.raised?2:8);}
    private static double originZ(Group group){return group.z*32.0*(1<<group.lod)*(group.raised?2:8);}
    private RetainedGroups.Item<Geometry> build(Group group){
        double ox=originX(group),oz=originZ(group);
        int[] count={0};
        var buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
        emit(group.parts,group.raised,null,(part,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->{
            float u=(part.slot%32*EDGE)/(float)SIZE,v=(part.slot/32*EDGE)/(float)SIZE;
            float a=u+(float)(u0/SIZE),b=v+(float)(v0/SIZE),c=u+(float)(u1/SIZE),d=v+(float)(v1/SIZE);
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
                if(raised)for(var neighbor:part.adjacent)if(neighbor!=null)min=Math.min(min,neighbor.minSurface());
                if(!picker.mayHit(bx+x0*cell,bz+z0*cell,bx+x1*cell,bz+z1*cell,min,max))continue;
            }
            if(!raised){
                top(sink,part,bx,bz,cell,x0,z0,x1,z1,0);continue;
            }
            var mesh=raster.relief();
            workBudget-=mesh.topCount()+mesh.wallCount()+part.adjacent.size()*32;
            if(workBudget<0)return false;
            for(int i=0;i<mesh.topCount();i++){
                double left=Math.max(x0,mesh.topX(i)),right=Math.min(x1,mesh.topX(i)+mesh.topWidth(i));
                double near=Math.max(z0,mesh.topZ(i)),far=Math.min(z1,mesh.topZ(i)+mesh.topDepth(i));
                if(left<right&&near<far)top(sink,part,bx,bz,cell,left,near,right,far,mesh.topHeight(i));
            }
            for(int i=0;i<mesh.wallCount();i++){
                int x=mesh.wallX(i),z=mesh.wallZ(i),dir=mesh.wallDirection(i);
                var seg=ReliefMesh.clipWall(x,z,dir,x0,z0,x1,z1);
                if(seg!=null)wall(sink,part,bx,bz,cell,x,z,dir,mesh.wallTop(i),mesh.wallBottom(i),seg.x(),seg.z(),seg.length());
            }
            for(int dir=0;dir<part.adjacent.size();dir++){
                MapRaster adjacent=part.adjacent.get(dir);if(adjacent==null)continue;
                for(int i=0;i<32;i++){
                    int x=dir==0?0:dir==1?31:i,z=dir==2?0:dir==3?31:i;
                    int nx=dir==0?31:dir==1?0:i,nz=dir==2?31:dir==3?0:i;
                    int at=z*32+x,n=nz*32+nx;
                    float top=raster.surface(at),bottom=adjacent.surface(n);
                    if(raster.coverage(at)==0||adjacent.coverage(n)==0||!Float.isFinite(top)||!Float.isFinite(bottom)||top<=bottom)continue;
                    wall(sink,part,bx,bz,cell,x,z,dir,top,bottom,x+(dir==1?1:0),z+(dir==3?1:0),1);
                }
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
    MapRayPicker.Hit pick(MapCamera camera,double sx,double sy){
        if(!camera.equals(lastRenderedCamera)||lastRaised!=(camera.elevationDegrees()!=90))return null;
        var picker=new MapRayPicker(camera,sx,sy);
        emit(drawnParts,lastRaised,picker,(part,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->picker.face(part.raster,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1));
        return picker.result();
    }
    private boolean contourAnchorVisible(MapCamera camera,ContourLabelVisibility.Anchor anchor){
        if(!camera.equals(lastRenderedCamera))return false;
        var screen=camera.project(new MapCamera.World(anchor.x(),anchor.y(),anchor.z()));
        var picker=new MapRayPicker(camera,screen.x(),screen.y(),screen.depth()+.0001);
        boolean complete=emit(drawnParts,true,picker,(part,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1,light)->
            picker.face(part.raster,x0,y0,z0,x1,y1,z1,u0,v0,u1,v1),8192);
        return complete&&picker.result()==null;
    }
    /** Static world-relative vertices. Each camera movement changes just the affine transform. */
    void draw(GuiGraphics graphics,List<Key> visible,MapCamera camera,MapProjection projection,int width,int height,MapHudLayout hud){
        drawCalls=quads=0;lastRenderedCamera=null;drawnParts=List.of();
        if(texture==null){clearAnchors();return;}
        boolean raised=camera.elevationDegrees()!=90;
        graphics.flush();refresh(visible,raised);
        long start=System.nanoTime();int budget=2;
        for(Group group:wantedGroups){
            if(meshes.get(group)!=null)continue;
            if(budget==0||System.nanoTime()-start>=1_000_000)break;
            if(meshes.ensure(group,estimate(group),protectedGroups,this::build))budget--;
        }
        double depthScale=Math.min(1,70.0/(Math.hypot(width,height)/camera.pixelsPerBlock()*2+8192));
        RenderSystem.setShaderTexture(0,textureId);
        RenderSystem.setShaderColor(1,1,1,1);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        if(raised){RenderSystem.depthMask(true);RenderSystem.clear(256,Minecraft.ON_OSX);RenderSystem.enableDepthTest();RenderSystem.disableCull();}
        var rendered=new ArrayList<Part>();
        try{
            for(Group group:wantedGroups){
                Geometry mesh=meshes.get(group);
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
                drawCalls++;quads+=mesh.quads;rendered.addAll(group.parts);
            }
            drawnParts=List.copyOf(rendered);lastRenderedCamera=camera;
            if(raised&&layers.structuresActive()){
                if(!anchorDependencies.equals(drawnParts)){
                    var index=new HashMap<Key,List<Part>>();
                    for(var part:drawnParts)index.computeIfAbsent(part.target,k->new ArrayList<>(4)).add(part);
                    anchorParts=index;anchorDependencies=drawnParts;
                }
            }else clearAnchors();
            drawContours(graphics,Minecraft.getInstance().font,visible,camera,projection,width,height,depthScale);
        }finally{
            VertexBuffer.unbind();
            if(raised){RenderSystem.clear(256,Minecraft.ON_OSX);RenderSystem.enableCull();RenderSystem.enableDepthTest();}
            RenderSystem.disableBlend();
        }
        if(layers.visible(MapLayers.Layer.CONTOURS)&&contours!=null)
            contours.drawLabels(graphics,Minecraft.getInstance().font,camera,projection,width,height,hud,drawnParts,
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
    long meshUploads(){return meshes.builds();}
    long meshBytes(){return meshes.bytes();}
    private V4ContourLayer contours;
    void drawContours(GuiGraphics graphics,net.minecraft.client.gui.Font font,List<Key> visible,MapCamera camera,MapProjection projection,int width,int height,double depthScale){
        if(!layers.visible(MapLayers.Layer.CONTOURS))return;
        if(contours==null)contours=new V4ContourLayer();
        Set<Key> exact=new HashSet<>();
        for(var part:drawnParts)if(part.target.equals(part.patch.source()))exact.add(part.target);
        List<ContourOverlayCache.Input> inputs=new ArrayList<>();
        for(Key k:visible){
            if(!exact.contains(k))continue;
            var center=pages.get(k);
            if(center==null||center.empty())continue;
            inputs.add(new ContourOverlayCache.Input(center,
                contourNeighbor(exact,k,1,0),contourNeighbor(exact,k,0,1),contourNeighbor(exact,k,1,1)));
        }
        contours.draw(graphics,font,inputs,camera,projection,width,height,depthScale);
    }
    private MapRaster contourNeighbor(Set<Key> exact,Key k,int dx,int dz){
        Key neighbor=new Key(k.lod(),k.x()+dx,k.z()+dz);return exact.contains(neighbor)?pages.get(neighbor):null;
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
        if(contours!=null){contours.close();contours=null;}
        meshes.close();retained=List.of();wantedGroups=List.of();protectedGroups=Set.of();drawnParts=List.of();clearAnchors();
        if(textureId!=null)Minecraft.getInstance().getTextureManager().release(textureId);
        texture=null;textureId=null;slots.clear();free.clear();
    }
}
