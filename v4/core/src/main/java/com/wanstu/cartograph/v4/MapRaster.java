package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** Immutable, style-independent geographic page adapted to compact presentation channels.
 * This initial raster renderer is a reference, not a satellite imagery or terrain mesh implementation.
 */
public final class MapRaster {
    public enum Style { SATELLITE_MODERN,SATELLITE_ANCIENT,HAND_MODERN,HAND_ANCIENT,HAND_ANIME }
    private static final int N=1024;
    private static final int[] SRGB=new int[65536];
    private static final int[][] HAND={
        {0x8c8a7b,0x80a864,0x426a4e,0x96999a,0xd9c48c,0x528fbd,0xe4edf0,0xb4a08c},
        {0xb4a484,0xb5b780,0x78845a,0xa39b8b,0xdccba3,0x87a8aa,0xe8dfc6,0xb39476},
        {0xa7a69b,0xa2cc80,0x5d986d,0xb2acbf,0xefd9a0,0x75b7e3,0xf2f5ff,0xc19c8b}
    };
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
    private final boolean empty;
    private final ReliefMesh relief;
    private final float minSurface,maxSurface;
    private MapRaster(Key key,long generation,int[][] pixels,float[] surface,float[] ground,byte[] coverage){
        this.key=key;this.generation=generation;this.pixels=pixels;this.surface=surface;this.ground=ground;this.coverage=coverage;boolean any=false;for(byte b:coverage)if(b!=0){any=true;break;}empty=!any;relief=ReliefMesh.build(surface,coverage);
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
    public boolean empty(){return empty;}
    public ReliefMesh relief(){return relief;}
    public float minSurface(){return minSurface;}
    public float maxSurface(){return maxSurface;}
    private static int rgb(int r,int g,int b){return (r<<16)|(g<<8)|b;}
    private static int darken(int c,double factor){
        return rgb((int)Math.round(((c>>>16)&255)*factor),(int)Math.round(((c>>>8)&255)*factor),(int)Math.round((c&255)*factor));
    }
    private static int mix(int a,int b,double t){
        return rgb((int)Math.round(((a>>>16)&255)*(1-t)+((b>>>16)&255)*t),
            (int)Math.round(((a>>>8)&255)*(1-t)+((b>>>8)&255)*t),
            (int)Math.round((a&255)*(1-t)+(b&255)*t));
    }
    static MapRaster build(WorldGeography.RenderPage page){
        var source=page.data();int[][] pixels=new int[Style.values().length][N];
        float[] surface=new float[N],ground=new float[N];byte[] coverage=new byte[N];
        Arrays.fill(surface,Float.NaN);Arrays.fill(ground,Float.NaN);
        long area=1L<<(2*source.key().lod());
        for(int i=0;i<N;i++){
            var e=source.entryAt(i);if(e==null)continue;
            var a=e.stats();long known=a.known();
            int alpha=(int)Math.max(1,(known*255+area/2)/area);
            coverage[i]=(byte)alpha;
            if(a.surfaceCount()>0)surface[i]=(float)((double)a.surfaceSum()/a.surfaceCount());
            if(a.groundCount()>0)ground[i]=(float)((double)a.groundSum()/a.groundCount());
            int sat=rgb(SRGB[(int)(a.redSum()/known)],SRGB[(int)(a.greenSum()/known)],SRGB[(int)(a.blueSum()/known)]);
            // Depth modulation is based only on observed water and is shared by every style.
            double waterShade=a.wetCount()==0?1:1-.25*((double)a.wetCount()/known)*Math.min(1,(double)a.depthSum()/a.wetCount()/16);
            pixels[0][i]=alpha<<24|darken(sat,waterShade);
            pixels[1][i]=alpha<<24|darken(mix(sat,0xbca887,.32),waterShade);
            long[] hist=a.histogram();
            for(int style=0;style<3;style++){
                long r=0,g=0,b=0;
                for(int c=0;c<hist.length;c++){
                    int v=HAND[style][c];r+=hist[c]*((v>>>16)&255);g+=hist[c]*((v>>>8)&255);b+=hist[c]*(v&255);
                }
                pixels[style+2][i]=alpha<<24|darken(rgb((int)(r/known),(int)(g/known),(int)(b/known)),waterShade);
            }
        }
        return new MapRaster(source.key(),page.generation(),pixels,surface,ground,coverage);
    }
}
