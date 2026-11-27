package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** Immutable, style-independent geographic page adapted to compact presentation channels.
 * Palette evaluation uses observed aggregate facts; no style-specific collection or camera dependency.
 */
public final class MapRaster {
    public enum Style { SATELLITE_MODERN,SATELLITE_ANCIENT,HAND_MODERN,HAND_ANCIENT,HAND_ANIME }
    private static final int N=1024;
    private static final int[] SRGB=new int[65536];
    static{
        for(int i=0;i<SRGB.length;i++){
            double v=i/65535.0;
            SRGB[i]=(int)Math.round(255*(v<=.0031308?12.92*v:1.055*Math.pow(v,1/2.4)-.055));
        }
    }
    private final Key key;
    private final long generation;
    private final int[][] pixels;
    private final float[] surface,ground;
    private final byte[] coverage;
    private final BitSet completeGround,completeSurface;
    private final boolean empty;
    private final ReliefMesh relief;
    private final MapShorelines shorelines;
    private final float minSurface,maxSurface;
    private MapRaster(Key key,long generation,int[][] pixels,float[] surface,float[] ground,byte[] coverage,BitSet completeGround,BitSet completeSurface,MapShorelines shorelines){
        this.shorelines=shorelines;this.key=key;this.generation=generation;this.pixels=pixels;this.surface=surface;this.ground=ground;this.coverage=coverage;this.completeGround=completeGround;this.completeSurface=completeSurface;boolean any=false;for(byte b:coverage)if(b!=0){any=true;break;}empty=!any;relief=ReliefMesh.build(surface,coverage);
        float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
        for(int i=0;i<surface.length;i++)if(coverage[i]!=0&&Float.isFinite(surface[i])){min=Math.min(min,surface[i]);max=Math.max(max,surface[i]);}
        minSurface=min;maxSurface=max;
    }
    public Key key(){return key;}
    public long generation(){return generation;}
    public int argb(Style style,int slot){return pixels[style.ordinal()][slot];}
    public float surface(int slot){return surface[slot];}
    public float ground(int slot){return ground[slot];}
    public int coverage(int slot){return Byte.toUnsignedInt(coverage[slot]);}
    public boolean completeGround(int slot){return completeGround.get(slot);}
    public boolean completeSurface(int slot){return completeSurface.get(slot);}
    public boolean empty(){return empty;}
    public ReliefMesh relief(){return relief;}
    public MapShorelines shorelines(){return shorelines;}
    public float minSurface(){return minSurface;}
    public float maxSurface(){return maxSurface;}
    private static int rgb(int r,int g,int b){return (r<<16)|(g<<8)|b;}
    static MapRaster build(WorldGeography.RenderPage page){
        var source=page.data();int[][] pixels=new int[Style.values().length][N];
        float[] surface=new float[N],ground=new float[N];byte[] coverage=new byte[N],shore=new byte[N];BitSet completeGround=new BitSet(N),completeSurface=new BitSet(N);
        Arrays.fill(surface,Float.NaN);Arrays.fill(ground,Float.NaN);
        long area=1L<<(2*source.key().lod());
        for(int i=0;i<N;i++){
            var e=source.entryAt(i);if(e==null)continue;
            var a=e.stats();long known=a.known();
            int alpha=(int)Math.max(1,(known*255+area/2)/area);
            coverage[i]=(byte)alpha;
            if(a.groundCount()==area)completeGround.set(i);
            if(a.surfaceCount()==area)completeSurface.set(i);
            if(a.surfaceCount()>0)surface[i]=(float)((double)a.surfaceSum()/a.surfaceCount());
            if(a.groundCount()>0)ground[i]=(float)((double)a.groundSum()/a.groundCount());
            int sat=rgb(SRGB[(int)(a.redSum()/known)],SRGB[(int)(a.greenSum()/known)],SRGB[(int)(a.blueSum()/known)]);
            long[] histogram=a.histogram();
            shore[i]=MapShorelines.classify(a,area,histogram);
            MapStylePalette.color(pixels,i,alpha,sat,a,histogram);
        }
        return new MapRaster(source.key(),page.generation(),pixels,surface,ground,coverage,completeGround,completeSurface,MapShorelines.build(source.key(),shore));
    }
}
