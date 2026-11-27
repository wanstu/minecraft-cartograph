package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.MapContextLayout.*;

public final class MapContextLayoutTest {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args){
        check(actions(false,false,true,false).isEmpty(),"unknown surface cannot invent XYZ");
        check(actions(false,false,true,true).equals(List.of(Action.NEW_REGION)),"user region annotation independent of terrain");
        check(actions(false,true,true,false).equals(List.of(Action.ADD,Action.CENTER,Action.COPY)),"observed surface actions");
        check(actions(true,false,true,true).equals(List.of(Action.EDIT,Action.CENTER,Action.COPY,Action.NEW_REGION)),"marker actions");
        check(actions(false,true,false,false).equals(List.of(Action.CENTER,Action.COPY)),"busy marker store omits mutation");
        int cases=0;
        for(int w:new int[]{1,24,100,160,320,640,1920})for(int h:new int[]{1,24,60,95,128,180,240,360,1080})
            for(int x:new int[]{-100,0,100,2000})for(int y:new int[]{-100,0,100,2000})for(int first:new int[]{-1,0,2,99}){
                for(int count=0;count<=4;count++){
                    var l=menu(w,h,x,y,count,first);var r=l.bounds();
                    check(r.x()>=0&&r.y()>=0&&r.x()+r.w()<=w&&r.y()+r.h()<=h,"menu bounds");
                    check(l.first()>=0&&l.first()+l.rows()<=count,"menu scroll");
                    check(l.indexAt(r.x()-1,r.y())==-1,"outside dismiss");
                    for(int i=0;i<l.rows();i++){
                        var row=l.row(i);check(l.indexAt(row.x()+row.w()/2.,row.y()+10)==l.first()+i,"menu hit matches rows");
                    }
                    cases++;
                }
                for(int count:new int[]{4,5}){
                    var l=editor(w,h,x,y,count,first);var r=l.bounds();
                    check(r.x()>=0&&r.y()>=0&&r.x()+r.w()<=w&&r.y()+r.h()<=h,"editor bounds");
                    check(l.first()>=0&&l.first()+l.rows()<=count,"editor scroll");
                    for(int i=0;i<l.rows();i++){
                        var row=l.row(i);
                        check(row.y()>=r.y()+l.header()&&row.y()+row.h()<=r.y()+r.h()-l.footer(),"fields outside header/footer");
                    }
                    cases++;
                }
            }
        var normal=menu(640,360,638,358,4,0);
        check(normal.bounds().x()+normal.bounds().w()==638&&normal.bounds().y()+normal.bounds().h()==358,"flip around cursor at edges");
        var compact=editor(240,128,10,5,5,99);
        check(compact.rows()==2&&compact.first()==3,"short editor reaches final fields");
        System.out.println("PASS: "+cases+" context/editor layout cases, observed target policy, busy-state actions and compact scroll");
    }
}
