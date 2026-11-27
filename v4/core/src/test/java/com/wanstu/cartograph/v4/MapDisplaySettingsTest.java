package com.wanstu.cartograph.v4;

public final class MapDisplaySettingsTest {
    private static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args){
        var s=new MapDisplaySettings();
        check(s.markerSize(.003)==14&&s.markerSize(16)==14,"fixed marker default");
        s.followZoom(true);check(s.markerSize(.003)==7&&s.markerSize(16)==28,"bounded map scaling");
        s.markers(4);check(s.markerSize(16)==56,"marker hard cap");
        s.markers(.5);check(s.markerSize(.003)==7,"marker minimum");
        s.labels(2.5);check(s.markerSize(1)==7,"independent labels");
        s.ui(20);check(s.labels()==2.5&&s.markers()==.5,"independent UI");
        int count=0;
        for(double scale:new double[]{1,1.25,1.5,2,3,4,8,20})
            for(int w:new int[]{80,240,320,640,854,1920,3840,7680})
                for(int h:new int[]{80,180,240,360,480,1080,2160,4320}){
                    s.ui(scale);var ui=MapDisplaySettings.Ui.of(s,w,h);
                    check(ui.scale()>=1&&ui.scale()<=scale,"effective UI bounded");
                    check(ui.width()*ui.scale()<=w+1e-8&&ui.height()*ui.scale()<=h+1e-8,"fits viewport");
                    var hud=MapHudLayout.scaled(ui,false);
                    for(var c:hud.controls()){
                        var r=c.bounds();double x=(r.x()+r.w()/2.)*ui.scale(),y=(r.y()+r.h()/2.)*ui.scale();
                        check(hud.actionAt(ui.logical(x),ui.logical(y))==c.action(),"scaled input hits drawn control");
                        check(!hud.permitsLabel((int)x,(int)y,0,0),"physical annotation avoids scaled UI");
                        for(var d:hud.controls())if(c!=d)check(!r.intersects(d.bounds()),"no control overlap");
                    }
                    check(PlayerHeading.SIZE==20,"player fixed independent of settings");
                    count++;
                }
        s.reset();check(s.ui()==1&&s.labels()==1&&s.markers()==1&&!s.followZoom(),"reset");
        try{s.ui(Double.NaN);throw new AssertionError("NaN");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: "+count+" scaled UI layouts, aligned click/label exclusion, independent marker/label/zoom and reset");
    }
}
