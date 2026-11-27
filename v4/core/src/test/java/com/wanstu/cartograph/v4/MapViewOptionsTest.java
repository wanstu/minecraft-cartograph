package com.wanstu.cartograph.v4;

import static com.wanstu.cartograph.v4.MapViewOptions.*;

public final class MapViewOptionsTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){
        var camera=new MapCamera(-1234.25,91.5,8765.75,3.25,315,90);
        var base=new Selection(MapRaster.Style.SATELLITE_MODERN,camera);
        int transitions=0;
        for(int i=0;i<5;i++){
            var style=styleAt(i);var styled=apply(base,style,false);
            check(styled.camera()==camera&&styled.style()==MapRaster.Style.values()[i],"style preserves camera and supported combination");
            for(var tilt:new Choice[]{Choice.FLAT,Choice.TILT_60,Choice.TILT_45,Choice.TILT_30}){
                var tilted=apply(styled,tilt,false);
                check(tilted.style()==styled.style()&&tilted.camera().elevationDegrees()==tilt.tilt(),"view preserves style");
                check(tilt.selected(tilted.style(),tilted.camera())&&style.selected(tilted.style(),tilted.camera()),"selected state");
                for(var turn:new Choice[]{Choice.ROTATE_LEFT,Choice.ROTATE_RIGHT,Choice.NORTH_UP}){
                    var next=apply(tilted,turn,false);
                    var c=next.camera();
                    check(c.x()==camera.x()&&c.y()==camera.y()&&c.z()==camera.z()&&c.pixelsPerBlock()==camera.pixelsPerBlock(),"center and scale invariant");
                    check(next.style()==styled.style()&&c.elevationDegrees()==tilt.tilt(),"rotation independent");
                    double expected=turn==Choice.NORTH_UP?0:turn==Choice.ROTATE_LEFT?270:0;
                    check(c.yawDegrees()==expected,"yaw wrap or north");
                    transitions++;
                }
            }
        }
        var s=base;
        for(int angle:new int[]{60,45,30,90}){
            s=apply(s,nextTilt(s.camera()),false);check(s.camera().elevationDegrees()==angle,"tilt cycle");
        }
        for(var choice:new Choice[]{Choice.TILT_60,Choice.TILT_45,Choice.TILT_30})
            check(!choice.enabled(true)&&apply(base,choice,true)==base,"region selection prevents tilt");
        check(Choice.FLAT.enabled(true)&&Choice.ROTATE_LEFT.enabled(true),"flat editing remains usable");
        int layouts=0;
        for(int w:new int[]{1,24,100,188,240,640,1920})
            for(int h:new int[]{1,22,39,40,100,180,238,480,1080})
                for(int x:new int[]{-100,34,5000})
                    for(int y:new int[]{-100,65,5000})
                        for(int first:new int[]{-1,0,4,11,99}){
                            var l=menu(w,h,x,y,first);var r=l.bounds();
                            check(r.x()>=0&&r.y()>=0&&r.x()+r.w()<=w&&r.y()+r.h()<=h,"popup fits");
                            check(l.first()>=0&&l.first()+l.rows()<=Choice.values().length,"scroll clamped");
                            check(l.choiceAt(r.x(),r.y())==null&&l.choiceAt(r.x()-1,r.y())==null,"header and outside not choices");
                            for(int i=0;i<l.rows();i++){
                                var row=l.row(i);
                                check(l.choiceAt(row.x()+row.w()/2.,row.y()+9)==Choice.values()[l.first()+i],"render and hit agree");
                            }
                            layouts++;
                        }
        System.out.println("PASS: "+transitions+" style/tilt/rotation combinations, region editing guard, "+layouts+" popup layout cases");
    }
}
