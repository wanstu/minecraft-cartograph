package com.wanstu.cartograph.v4;
import java.util.*;
public final class MapProjectionTest{
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    public static void main(String[] args){
        var flat=new MapCamera(-17,72,35,.5,0,90);
        check(new MapProjection(flat).plan(960,540,-64,320,384).equals(MapViewport.plan(-17,35,.5,960,540,384)),"north-up parity");
        Random r=new Random(27);
        for(int i=0;i<2000;i++){
            var c=new MapCamera(r.nextInt(-29_000_000,29_000_000),70,r.nextInt(-29_000_000,29_000_000),.01+r.nextDouble()*8,r.nextDouble()*360,30+r.nextDouble()*60);
            var p=new MapProjection(c);var bounds=p.bounds(960,540,-64,320);var plan=p.plan(960,540,-64,320,64);
            double x=r.nextDouble()*960-480,z=r.nextDouble()*540-270,y=r.nextDouble()*384-64;
            var w=c.atHeight(x,z,y);
            var original=c.project(w);var fast=p.project(w.x(),w.y(),w.z());
            check(Math.abs(original.x()-fast.x())<1e-7&&Math.abs(original.y()-fast.y())<1e-7&&Math.abs(original.depth()-fast.depth())<1e-7,"projection parity");
            check(w.x()>=bounds.minX()&&w.x()<=bounds.maxX()&&w.z()>=bounds.minZ()&&w.z()<=bounds.maxZ(),"conservative bounds");
            long size=32L<<plan.lod();
            var key=new RegionPageStore.Key(plan.lod(),(int)Math.floor(w.x()/size),(int)Math.floor(w.z()/size));
            check(plan.keys().size()<=64&&plan.keys().contains(key),"bounded actual visibility");
        }
        System.out.println("PASS: flat viewport parity, 2000 rotated/tilted projection and conservative visibility cases");
    }
}
