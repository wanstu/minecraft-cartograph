package com.wanstu.cartograph.v4;

/** Small local-space line glyphs; all vertices stay inside the symbol's observed 8x8 footprint. */
public final class MapLandformGlyphs {
    private MapLandformGlyphs(){}
    @FunctionalInterface public interface Line {void emit(double x0,double z0,double x1,double z1,int argb);}
    private static final double[][][] PATHS={
        // canopy, hill, peak
        {{-1.5,.3,0,-1.7,1.5,.3,.6,.3,.6,1.3,-.6,1.3,-.6,.3,-1.5,.3},{-2,1,0,-1.2,2,1},{-2,1,0,-2,2,1,-2,1}},
        {{-1.6,.4,-.8,-1,0,-1.8,.8,-1,1.6,.4,-1.6,.4,0,.4,0,1.6},{-2,1,-.8,-.4,0,-1.2,.8,-.4,2,1},{-2,1,0,-2,2,1,0,-.4,-.8,.4}},
        {{-1.6,.2,-1.2,-.9,0,-1.6,1.2,-.9,1.6,.2,.6,.6,-.6,.6,-1.6,.2,0,.2,0,1.5},{-2,1,-.8,-.5,0,-1.1,.8,-.5,2,1},{-2,1,0,-2,2,1,.5,-.1,0,-.8,-.5,-.1}}
    };
    public static void emit(MapLandforms.Symbol symbol,MapRaster.Style style,Line line){
        int variant=switch(style){case HAND_MODERN->0;case HAND_ANCIENT->1;case HAND_ANIME->2;default->-1;};
        if(variant<0)return;
        int color=switch(style){
            case HAND_MODERN->symbol.kind()==MapLandforms.Kind.CANOPY?0xcf3b6647:0xc15b6261;
            case HAND_ANCIENT->symbol.kind()==MapLandforms.Kind.CANOPY?0xe0606947:0xd06e5943;
            case HAND_ANIME->symbol.kind()==MapLandforms.Kind.CANOPY?0xd2317655:0xc675648b;
            default->0;
        };
        double[] points=PATHS[variant][symbol.kind().ordinal()];
        double x=symbol.x()+.5,z=symbol.z()+.5;
        for(int i=2;i<points.length;i+=2)line.emit(x+points[i-2],z+points[i-1],x+points[i],z+points[i+1],color);
    }
}
