package com.wanstu.cartograph.v4;

/** Presentation preferences only; world projection and collection are independent. */
public final class MapDisplaySettings {
    private double ui=1,labels=1,markers=1;
    private boolean followZoom;
    public double ui(){return ui;}
    public double labels(){return labels;}
    public double markers(){return markers;}
    public boolean followZoom(){return followZoom;}
    public void ui(double value){ui=bounded(value,1,20);}
    public void labels(double value){labels=bounded(value,.5,4);}
    public void markers(double value){markers=bounded(value,.5,4);}
    public void followZoom(boolean value){followZoom=value;}
    public void reset(){ui=labels=markers=1;followZoom=false;}
    private static double bounded(double value,double min,double max){
        if(!Double.isFinite(value))throw new IllegalArgumentException("Scale");
        return Math.max(min,Math.min(max,value));
    }
    /** Keep controls usable in small windows; requested scale is retained for larger windows. */
    public double effectiveUi(int width,int height){
        return Math.min(ui,Math.max(1,Math.min(width/320.0,height/180.0)));
    }
    public int markerSize(double pixelsPerBlock){
        double relative=followZoom?Math.max(.5,Math.min(2,Math.sqrt(pixelsPerBlock))):1;
        return Math.max(7,Math.min(56,(int)Math.round(14*markers*relative)));
    }
    public int markerRadius(double pixelsPerBlock){return (markerSize(pixelsPerBlock)+1)/2+1;}
    public record Ui(double scale,int width,int height){
        public static Ui of(MapDisplaySettings settings,int width,int height){
            double scale=settings.effectiveUi(width,height);
            return new Ui(scale,Math.max(1,(int)Math.floor(width/scale)),Math.max(1,(int)Math.floor(height/scale)));
        }
        public double logical(double coordinate){return coordinate/scale;}
        public int pixel(double logical){return (int)Math.round(logical*scale);}
    }
}
