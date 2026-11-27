package com.wanstu.cartograph.v4;

import java.util.*;

/** Reusable, bounded screen-space occupancy. Reserve symbols before lower-priority text. */
public final class MapLabelLayout {
    public static final int MAX_RECTS=384;
    private final int[] xs=new int[MAX_RECTS],ys=new int[MAX_RECTS],rights=new int[MAX_RECTS],bottoms=new int[MAX_RECTS];
    private int count;
    private MapHudLayout hud;
    public void begin(MapHudLayout hud){this.hud=Objects.requireNonNull(hud);count=0;}
    public boolean fits(int x,int y,int halfW,int halfH){
        if(count>=MAX_RECTS||halfW<0||halfH<0||!hud.permitsLabel(x,y,halfW,halfH))return false;
        int left=x-halfW,top=y-halfH,right=x+halfW+1,bottom=y+halfH+1;
        for(int i=0;i<count;i++)if(left<rights[i]&&right>xs[i]&&top<bottoms[i]&&bottom>ys[i])return false;
        return true;
    }
    public boolean reserve(int x,int y,int halfW,int halfH){
        if(!fits(x,y,halfW,halfH))return false;
        xs[count]=x-halfW;ys[count]=y-halfH;rights[count]=x+halfW+1;bottoms[count]=y+halfH+1;count++;
        return true;
    }
    public int count(){return count;}
}
