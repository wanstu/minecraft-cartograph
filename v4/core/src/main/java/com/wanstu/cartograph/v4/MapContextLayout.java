package com.wanstu.cartograph.v4;

import java.util.*;

/** Context menu and editor panel geometry in logical GUI pixels. */
public final class MapContextLayout {
    private MapContextLayout(){}
    public enum Action { ADD,EDIT,CENTER,COPY,NEW_REGION }
    public static List<Action> actions(boolean marker,boolean observed,boolean markersReady,boolean regionsReady){
        List<Action> out=new ArrayList<>(4);
        if(marker){if(markersReady)out.add(Action.EDIT);out.add(Action.CENTER);out.add(Action.COPY);}
        else if(observed){if(markersReady)out.add(Action.ADD);out.add(Action.CENTER);out.add(Action.COPY);}
        if(regionsReady)out.add(Action.NEW_REGION);
        return List.copyOf(out);
    }
    public record Menu(MapHudLayout.Rect bounds,int rows,int first){
        public MapHudLayout.Rect row(int index){
            if(index<0||index>=rows)throw new IndexOutOfBoundsException(index);
            return new MapHudLayout.Rect(bounds.x(),bounds.y()+index*20,bounds.w(),20);
        }
        public int indexAt(double x,double y){
            return bounds.contains(x,y)?first+(int)((y-bounds.y())/20):-1;
        }
    }
    public static Menu menu(int width,int height,int x,int y,int count,int first){
        if(width<1||height<1||count<0||count>Action.values().length)throw new IllegalArgumentException("Menu");
        int w=Math.min(166,width),rows=Math.min(count,height/20),h=rows*20;
        if(x+w>width)x-=w;if(y+h>height)y-=h;
        x=Math.max(0,Math.min(x,width-w));y=Math.max(0,Math.min(y,height-h));
        return new Menu(new MapHudLayout.Rect(x,y,w,h),rows,Math.max(0,Math.min(first,count-rows)));
    }
    /** Editor body is scrollable by whole rows; header/footer are separate. */
    public record Editor(MapHudLayout.Rect bounds,int header,int footer,int rows,int first){
        public MapHudLayout.Rect row(int index){
            if(index<0||index>=rows)throw new IndexOutOfBoundsException(index);
            return new MapHudLayout.Rect(bounds.x()+4,bounds.y()+header+index*32,Math.max(1,bounds.w()-8),32);
        }
    }
    public static Editor editor(int width,int height,int x,int y,int count,int first){
        if(width<1||height<1||count<1||count>16)throw new IllegalArgumentException("Editor");
        int w=Math.min(316,width),header=Math.min(23,height),footer=Math.min(40,height-header);
        int rows=Math.min(count,Math.max(0,(height-header-footer)/32)),h=header+rows*32+footer;
        x=Math.max(0,Math.min(x,width-w));y=Math.max(0,Math.min(y,height-h));
        return new Editor(new MapHudLayout.Rect(x,y,w,h),header,footer,rows,Math.max(0,Math.min(first,count-rows)));
    }
}
