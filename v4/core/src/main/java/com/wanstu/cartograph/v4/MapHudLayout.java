package com.wanstu.cartograph.v4;

import java.util.*;

/** One immutable layout for drawing, hit tests and overlay exclusion in GUI pixels. */
public final class MapHudLayout {
    public enum Action { LAYERS, VIEW, MARKERS, REGIONS, DIAGNOSTICS, ZOOM_IN, ZOOM_OUT, CENTER, NORTH, CLOSE }
    public record Rect(int x,int y,int w,int h){
        public boolean contains(double px,double py){return px>=x&&py>=y&&px<x+w&&py<y+h;}
        public boolean intersects(Rect other){return x<other.x+other.w&&x+w>other.x&&y<other.y+other.h&&y+h>other.y;}
    }
    public record Control(Action action,Rect bounds){}
    private final Rect header,status,debug;
    private final List<Control> controls;
    private final int width,height;
    public MapHudLayout(int width,int height,boolean diagnostics){
        if(width<1||height<1)throw new IllegalArgumentException("Viewport");
        this.width=width;this.height=height;
        header=new Rect(0,0,width,Math.min(32,height));
        status=new Rect(0,Math.max(0,height-18),width,Math.min(height,18));
        List<Control> buttons=new ArrayList<>();
        int size=Math.min(24,Math.min(Math.max(0,(width-12)/3),Math.max(0,(height-68)/5)));
        if(size>=10){
            Action[] left={Action.LAYERS,Action.VIEW,Action.MARKERS,Action.REGIONS,Action.DIAGNOSTICS};
            Action[] right={Action.ZOOM_IN,Action.ZOOM_OUT,Action.CENTER,Action.NORTH};
            for(int i=0;i<left.length;i++)buttons.add(new Control(left[i],new Rect(6,38+i*(size+3),size,size)));
            for(int i=0;i<right.length;i++)buttons.add(new Control(right[i],new Rect(width-6-size,height-24-(4-i)*size-(3-i)*3,size,size)));
        }
        if(width>=32&&height>=26)buttons.add(new Control(Action.CLOSE,new Rect(width-26,4,22,22)));
        controls=List.copyOf(buttons);
        int dw=Math.min(300,Math.max(0,width-80)),dh=Math.min(84,Math.max(0,height-64));
        debug=diagnostics&&dw>=70&&dh>=20?new Rect((width-dw)/2,36,dw,dh):new Rect(0,0,0,0);
    }
    public Rect header(){return header;}
    public Rect status(){return status;}
    public Rect debug(){return debug;}
    public List<Control> controls(){return controls;}
    public Action actionAt(double x,double y){for(var c:controls)if(c.bounds.contains(x,y))return c.action;return null;}
    public boolean blocks(double x,double y){return header.contains(x,y)||status.contains(x,y)||debug.contains(x,y)||actionAt(x,y)!=null;}
    public boolean permitsLabel(int x,int y,int halfWidth,int halfHeight){
        if(x-halfWidth<0||y-halfHeight<0||x+halfWidth>=width||y+halfHeight>=height)return false;
        var r=new Rect(x-halfWidth,y-halfHeight,2*halfWidth+1,2*halfHeight+1);
        if(header.intersects(r)||status.intersects(r)||(debug.w>0&&debug.intersects(r)))return false;
        for(var c:controls)if(c.bounds.intersects(r))return false;
        return true;
    }
    /** A drag can only pan if its initiating press belonged to the map. */
    public static final class Gesture{
        private boolean mapDrag;
        public void press(boolean map,int button){if(button==0)mapDrag=map;}
        public boolean canPan(int button){return button==0&&mapDrag;}
        public void release(int button){if(button==0)mapDrag=false;}
        public void cancel(){mapDrag=false;}
    }
}
