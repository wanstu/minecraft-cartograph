package com.wanstu.cartograph.v4;

public final class SurfaceChangeFilterTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        check(SurfaceChangeFilter.irrelevant(20,80,70,50,-64,false),"deep change");
        check(!SurfaceChangeFilter.irrelevant(69,80,70,50,-64,true),"ground margin");
        check(!SurfaceChangeFilter.irrelevant(80,80,70,50,-64,true),"top change");
        check(!SurfaceChangeFilter.irrelevant(51,80,70,50,-64,false),"water floor change");
        check(SurfaceChangeFilter.irrelevant(51,80,70,50,-64,true),"dry surface does not use ocean floor");
        check(!SurfaceChangeFilter.irrelevant(20,80,-65,50,-64,true),"unknown relief");
        check(!SurfaceChangeFilter.irrelevant(20,80,70,-65,-64,false),"unknown floor");
        check(!SurfaceChangeFilter.irrelevant(-64,-63,-63,-63,-64,true),"bottom margin");
        for(int y=-64;y<=100;y++){
            boolean expected=y<49;
            check(SurfaceChangeFilter.irrelevant(y,80,70,50,-64,false)==expected,"strict water floor");
            check(SurfaceChangeFilter.irrelevant(y,80,70,50,-64,true)==(y<69),"strict dry margin");
        }
        System.out.println("PASS: conservative top/relief/floor mutation filtering, dry/water separation, margins and unknown heights");
    }
}
