package com.wanstu.cartograph.v4;

import java.util.*;

/** Shared monochrome vector strokes, rasterized once into a small transparent icon atlas. */
public final class MapIcons {
    public static final int SIZE=16,COLUMNS=8;
    public enum Icon {
        LAYERS,VIEW,PIN,REGION,INFO,PLUS,MINUS,CENTER,NORTH,CLOSE,
        HOME,FLAG,MINE,PORT,FARM,LANDMARK,WATER,UNDERGROUND,PORTAL,NETHER,END,OTHER,
        EDIT,COPY,PLAYER_BORDER,PLAYER_FILL
    }
    private static final String[] PATHS={
        "2,6 8,2 14,6 8,10 2,6;2,9 8,13 14,9;2,12 8,15 14,12",
        "2,5 8,2 14,5 14,11 8,14 2,11 2,5;2,5 8,8 14,5;8,8 8,14",
        "8,14 3,7 3,5 5,3 11,3 13,5 13,7 8,14;8,5 9,6 8,7 7,6 8,5",
        "3,3 13,3 13,13 3,13 3,3;2,2 4,2 4,4 2,4 2,2;12,12 14,12 14,14 12,14 12,12",
        "8,1 12,3 14,7 13,11 10,14 6,14 3,11 2,7 4,3 8,1;8,7 8,11;8,4 8,4",
        "3,8 13,8;8,3 8,13",
        "3,8 13,8",
        "8,2 8,5;8,11 8,14;2,8 5,8;11,8 14,8;8,5 11,8 8,11 5,8 8,5",
        "8,2 13,13 8,10 3,13 8,2;8,3 8,10",
        "3,3 13,13;13,3 3,13",
        "2,7 8,2 14,7;4,6 4,14 12,14 12,6;7,14 7,10 9,10 9,14",
        "4,14 4,2 12,2 11,5 12,8 4,8",
        "3,13 10,6;4,3 8,3 13,8 13,12;3,3 5,6 8,7 11,7",
        "8,2 8,13;6,4 10,4;3,8 3,11 5,13 8,14 11,13 13,11 13,8;2,9 3,8 4,9;12,9 13,8 14,9",
        "8,14 8,3;8,6 4,3 3,5 8,8;8,9 12,5 13,7 8,11",
        "2,14 2,4 5,4 5,7 7,7 7,4 10,4 10,7 12,7 12,4 14,4 14,14 2,14;7,14 7,10 9,10 9,14",
        "2,10 5,12 8,10 11,12 14,10;2,13 5,15 8,13 11,15 14,13;4,8 8,3 12,8 4,8",
        "2,14 2,7 5,3 11,3 14,7 14,14;5,14 5,8 11,8 11,14;8,8 8,14",
        "3,14 3,2 13,2 13,14 3,14;5,14 5,4 11,4 11,14",
        "2,14 2,6 5,6 5,3 7,3 7,8 9,8 9,3 11,3 11,6 14,6 14,14 2,14;6,14 6,10 10,10 10,14",
        "4,14 12,14;8,14 8,4;3,8 8,5 13,8 8,11 3,8;5,3 8,1 11,3 8,5 5,3",
        "8,2 14,8 8,14 2,8 8,2;8,5 8,9;8,11 8,11",
        "3,10 11,2 14,5 6,13 2,14 3,10;9,4 12,7",
        "5,2 13,2 13,11 10,11;3,5 10,5 10,14 3,14 3,5"
    };
    public static Icon toolbar(MapHudLayout.Action action){
        return switch(action){
            case LAYERS->Icon.LAYERS;case VIEW->Icon.VIEW;case MARKERS->Icon.PIN;case REGIONS->Icon.REGION;
            case DIAGNOSTICS->Icon.INFO;case ZOOM_IN->Icon.PLUS;case ZOOM_OUT->Icon.MINUS;
            case CENTER->Icon.CENTER;case NORTH->Icon.NORTH;case CLOSE->Icon.CLOSE;
        };
    }
    public static Icon marker(MarkerStore.Icon icon){
        return switch(icon){case POINT->Icon.PIN;case HOME->Icon.HOME;case FLAG->Icon.FLAG;case MINE->Icon.MINE;case PORT->Icon.PORT;case FARM->Icon.FARM;};
    }
    public static Icon structure(StructureCatalog.Category category){
        return switch(category){case SETTLEMENT->Icon.HOME;case LANDMARK->Icon.LANDMARK;case WATER->Icon.WATER;case UNDERGROUND->Icon.UNDERGROUND;case PORTAL->Icon.PORTAL;case NETHER->Icon.NETHER;case END->Icon.END;case OTHER->Icon.OTHER;};
    }
    public static Icon context(MapContextLayout.Action action){
        return switch(action){case ADD->Icon.PLUS;case EDIT->Icon.EDIT;case CENTER->Icon.CENTER;case COPY->Icon.COPY;case NEW_REGION->Icon.REGION;};
    }
    public static int rows(){return (Icon.values().length+COLUMNS-1)/COLUMNS;}
    private static byte[] playerMask(boolean border){
        // North-pointing, symmetric concave dart. Inner mask leaves a narrow contrast edge.
        double[] xs=border?new double[]{8,14,8,2}:new double[]{8,12.5,8,3.5};
        double[] ys=border?new double[]{1,14,11,14}:new double[]{2.5,12.2,10,12.2};
        byte[] out=new byte[SIZE*SIZE];
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
            int n=0;
            for(int sy=0;sy<4;sy++)for(int sx=0;sx<4;sx++){
                double px=x+(sx+.5)/4,py=y+(sy+.5)/4;
                boolean inside=false;
                for(int i=0,j=3;i<4;j=i++)
                    if((ys[i]>py)!=(ys[j]>py)&&px<(xs[j]-xs[i])*(py-ys[i])/(ys[j]-ys[i])+xs[i])inside=!inside;
                if(inside)n++;
            }
            out[y*SIZE+x]=(byte)((n*255+8)/16);
        }
        return out;
    }
    /** A fixed 4x4 subpixel coverage calculation with a single stroke weight. */
    public static byte[] raster(Icon icon){
        if(icon==Icon.PLAYER_BORDER||icon==Icon.PLAYER_FILL)return playerMask(icon==Icon.PLAYER_BORDER);
        List<double[]> segments=new ArrayList<>();
        for(String path:PATHS[icon.ordinal()].split(";")){
            String[] points=path.split(" ");
            for(int i=1;i<points.length;i++){
                String[] a=points[i-1].split(","),b=points[i].split(",");
                segments.add(new double[]{Double.parseDouble(a[0]),Double.parseDouble(a[1]),Double.parseDouble(b[0]),Double.parseDouble(b[1])});
            }
        }
        byte[] pixels=new byte[SIZE*SIZE];
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
            int count=0;
            for(int sy=0;sy<4;sy++)for(int sx=0;sx<4;sx++){
                double px=x+(sx+.5)/4,py=y+(sy+.5)/4;
                for(var s:segments){
                    double dx=s[2]-s[0],dy=s[3]-s[1],len=dx*dx+dy*dy;
                    double t=len==0?0:Math.max(0,Math.min(1,((px-s[0])*dx+(py-s[1])*dy)/len));
                    double ex=px-s[0]-t*dx,ey=py-s[1]-t*dy;
                    if(ex*ex+ey*ey<=.75*.75){count++;break;}
                }
            }
            pixels[y*SIZE+x]=(byte)((count*255+8)/16);
        }
        return pixels;
    }
}
