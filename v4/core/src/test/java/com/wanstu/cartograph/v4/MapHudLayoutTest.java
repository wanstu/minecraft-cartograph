package com.wanstu.cartograph.v4;

import static com.wanstu.cartograph.v4.MapHudLayout.*;

public final class MapHudLayoutTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        int cases=0;
        for(int width:new int[]{1,32,80,120,160,240,320,426,640,854,1280,1920})
            for(int height:new int[]{1,24,60,100,120,180,240,360,480,720,1080})
                for(boolean debug:new boolean[]{false,true}){
                    var layout=new MapHudLayout(width,height,debug);
                    for(var c:layout.controls()){
                        var r=c.bounds();
                        check(r.x()>=0&&r.y()>=0&&r.x()+r.w()<=width&&r.y()+r.h()<=height,"control within viewport");
                        check(layout.actionAt(r.x()+r.w()/2.,r.y()+r.h()/2.)==c.action(),"same draw and hit region");
                        check(layout.blocks(r.x(),r.y()),"UI blocks map input");
                        check(!layout.permitsLabel(r.x()+r.w()/2,r.y()+r.h()/2,0,0),"label excluded");
                        for(var other:layout.controls())if(c!=other)check(!r.intersects(other.bounds()),"control overlap");
                        if(c.action()!=Action.CLOSE){
                            check(!r.intersects(layout.header())&&!r.intersects(layout.status()),"toolbar outside bars");
                            if(layout.debug().w()>0)check(!r.intersects(layout.debug()),"toolbar outside diagnostics");
                        }
                    }
                    cases++;
                }
        var hud=new MapHudLayout(640,360,false);
        check(!hud.blocks(320,180)&&hud.permitsLabel(320,180,30,8),"unblocked central map");
        check(!hud.permitsLabel(320,34,30,5),"label bounds intersect header");
        check(!hud.permitsLabel(0,180,10,5),"label offscreen");
        check(hud.controls().size()==10,"normal size all controls");
        var gesture=new Gesture();
        gesture.press(false,0);check(!gesture.canPan(0),"toolbar-start drag blocked even outside");
        gesture.release(0);gesture.press(true,0);check(gesture.canPan(0)&&!gesture.canPan(1),"map-only left drag");
        gesture.release(1);check(gesture.canPan(0),"unrelated release");
        gesture.release(0);check(!gesture.canPan(0),"release");
        gesture.press(true,0);gesture.cancel();check(!gesture.canPan(0),"popup/resize cancels");
        System.out.println("PASS: "+cases+" HUD size/diagnostic combinations, shared hit/label exclusion and gesture ownership");
    }
}
