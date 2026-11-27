package com.wanstu.cartograph.v4;

import static com.wanstu.cartograph.v4.RegionPageStore.Aggregate;

/** Artist-defined palettes evaluated from observed, weighted terrain facts.
 * Independent of camera, tile coordinates, style selection and world generation.
 */
final class MapStylePalette {
    private static final int[][] COLORS={
        // OTHER, GRASS, FOREST, ROCK, SAND, WATER, SNOW, BUILT
        {0xb1a58f,0xc6bb89,0x7c8964,0x9b9083,0xe5cfa7,0x6896a5,0xe9e3ce,0xad927b},
        {0xb6b7aa,0xaac78b,0x68966d,0x9c9e99,0xe3d1a6,0x609dc6,0xeaf1f3,0xa89a8b},
        {0xd1bfa1,0xcfc396,0x879265,0xb4a28a,0xe4cfaa,0x86a7b3,0xf0e5cc,0xb99c7e},
        {0xb3aaa9,0xb1dc88,0x58aa77,0xb6a7c6,0xf4dba1,0x6fbcf0,0xf2f6ff,0xc79e8d}
    };
    private MapStylePalette(){}
    static int rgb(int r,int g,int b){return r<<16|g<<8|b;}
    private static int shade(int c,double factor){
        return rgb((int)Math.round(((c>>>16)&255)*factor),(int)Math.round(((c>>>8)&255)*factor),(int)Math.round((c&255)*factor));
    }
    private static int mix(int a,int b,double t){
        return rgb((int)Math.round(((a>>>16)&255)*(1-t)+((b>>>16)&255)*t),
            (int)Math.round(((a>>>8)&255)*(1-t)+((b>>>8)&255)*t),
            (int)Math.round((a&255)*(1-t)+(b&255)*t));
    }
    private static int weighted(int[] palette,long[] hist,long known){
        long r=0,g=0,b=0;
        for(int c=0;c<hist.length;c++){
            long n=hist[c];if(n==0)continue;
            int v=palette[c];r+=n*((v>>>16)&255);g+=n*((v>>>8)&255);b+=n*(v&255);
        }
        return rgb((int)((r+known/2)/known),(int)((g+known/2)/known),(int)((b+known/2)/known));
    }
    static void color(int[][] pixels,int slot,int alpha,int satellite,Aggregate a){
        long known=a.known();long[] histogram=a.histogram();
        double wet=(double)a.wetCount()/known;
        // Modulate by water fraction and observed mean water depth, not inferred ocean/river identity.
        double depth=a.wetCount()==0?0:Math.min(1,(double)a.depthSum()/a.wetCount()/16);
        pixels[0][slot]=alpha<<24|shade(satellite,1-.25*wet*depth);
        // Ancient satellite retains material detail and mixes with terrain-specific ink/paper tones.
        // A water cell is never blended into a generic brown paper tint.
        int antique=weighted(COLORS[0],histogram,known);
        pixels[1][slot]=alpha<<24|shade(mix(satellite,antique,.38),1-.23*wet*depth);
        // Only valid ground for every known sample contributes coarse relief contrast. No neighbor reads,
        // no page-local normalization and no invented ridges in unknown areas.
        double roughness=a.groundCount()==known?Math.min(1,((long)a.maxGround()-a.minGround())/48.0):0;
        long land=known-histogram[FeatureDictionary.Terrain.WATER.ordinal()];
        double landRelief=roughness*(double)land/known*(1-wet);
        for(int i=1;i<COLORS.length;i++){
            int c=weighted(COLORS[i],histogram,known);
            double contrast=switch(i){case 1->.09;case 2->.12;default->.07;};
            double water=switch(i){case 1->.22;case 2->.18;default->.26;};
            pixels[i+1][slot]=alpha<<24|shade(c,1-water*wet*depth-contrast*landRelief);
        }
    }
}
