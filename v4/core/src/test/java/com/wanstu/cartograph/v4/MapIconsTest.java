package com.wanstu.cartograph.v4;

import java.util.*;

public final class MapIconsTest {
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        Set<String> distinct=new HashSet<>();
        for(var icon:MapIcons.Icon.values()){
            byte[] a=MapIcons.raster(icon);
            check(a.length==256,"icon extent");
            check(Arrays.equals(a,MapIcons.raster(icon)),"deterministic raster");
            int visible=0,clear=0,partial=0;
            for(byte p:a){int alpha=p&255;if(alpha==0)clear++;else visible++;if(alpha>0&&alpha<255)partial++;}
            check(visible>10&&clear>64&&partial>0,"thin transparent anti-aliased stroke");
            check(distinct.add(Base64.getEncoder().encodeToString(a)),"distinct icon shapes");
        }
        for(var a:MapHudLayout.Action.values())check(MapIcons.toolbar(a)!=null,"toolbar coverage");
        for(var a:MarkerStore.Icon.values())check(MapIcons.marker(a)!=null,"marker coverage");
        for(var a:StructureCatalog.Category.values())check(MapIcons.structure(a)!=null,"structure coverage");
        for(var a:MapContextLayout.Action.values())check(MapIcons.context(a)!=null,"context coverage");
        var layout=new MapLabelLayout();var hud=new MapHudLayout(1024,768,false);
        layout.begin(hud);
        check(layout.reserve(500,400,8,8),"player symbol");
        check(!layout.reserve(506,400,8,8),"symbol avoids player");
        check(layout.reserve(520,400,8,8),"adjacent symbol");
        check(!layout.reserve(500,400,40,5),"text avoids symbols");
        check(layout.reserve(500,418,40,5),"text below symbol");
        check(!layout.reserve(550,418,40,5),"text avoids text");
        check(!layout.reserve(500,10,20,5),"header excluded");
        check(!layout.reserve(0,400,8,8),"offscreen excluded");
        layout.begin(hud);check(layout.count()==0,"frame reset");
        int count=0;
        for(int y=60;y<700;y+=12)for(int x=50;x<970;x+=12)if(layout.reserve(x,y,3,3))count++;
        check(count==MapLabelLayout.MAX_RECTS&&layout.count()==count,"bounded occupancy");
        check(!layout.reserve(900,700,1,1),"cap rejects extras");
        System.out.println("PASS: "+distinct.size()+" unique monochrome icons, complete mappings and shared bounded label collision");
    }
}
