package com.wanstu.cartograph.v4;

import java.util.Arrays;

/**
 * Immutable height-field topology built once per raster generation, independent of style and camera.
 * Top rectangles greedily merge equal known heights. Interior risers use the higher cell's color.
 * Missing neighbors never invent a cliff. Patch-boundary risers are resolved from actually drawn patches by ReliefSeams.
 */
public final class ReliefMesh {
    public static final int EDGE=32;
    // Top: packed x,z,width,depth (six bits each), then height.
    // Wall: packed cell x,z, direction (0 west,1 east,2 north,3 south), then top/bottom.
    private final int[] tops;
    private final float[] topY;
    private final short[] walls;
    private final float[] wallY;
    private ReliefMesh(int[] tops,float[] topY,short[] walls,float[] wallY){
        this.tops=tops;this.topY=topY;this.walls=walls;this.wallY=wallY;
    }
    static ReliefMesh build(float[] height,byte[] known){
        if(height.length!=1024||known.length!=1024)throw new IllegalArgumentException("Page size");
        int[] tops=new int[1024];float[] ys=new float[1024];
        short[] walls=new short[2048];float[] wallY=new float[4096];
        boolean[] used=new boolean[1024];int nt=0,nw=0;
        for(int z=0;z<32;z++)for(int x=0;x<32;x++){
            int i=z*32+x;float y=height[i];
            if(known[i]==0||!Float.isFinite(y))continue;
            if(!used[i]){
                int w=1,d=1;
                while(x+w<32&&same(i+w,y,height,known,used))w++;
                rows:while(z+d<32){
                    for(int j=0;j<w;j++)if(!same(i+d*32+j,y,height,known,used))break rows;
                    d++;
                }
                for(int r=0;r<d;r++)Arrays.fill(used,i+r*32,i+r*32+w,true);
                tops[nt]=x|(z<<6)|(w<<12)|(d<<18);ys[nt++]=y;
            }
            if(x<31&&known[i+1]!=0&&Float.isFinite(height[i+1])&&y!=height[i+1]){
                boolean high=y>height[i+1];
                walls[nw]=(short)((high?x:x+1)|(z<<5)|((high?1:0)<<10));
                wallY[2*nw]=Math.max(y,height[i+1]);wallY[2*nw+1]=Math.min(y,height[i+1]);nw++;
            }
            if(z<31&&known[i+32]!=0&&Float.isFinite(height[i+32])&&y!=height[i+32]){
                boolean high=y>height[i+32];
                walls[nw]=(short)(x|((high?z:z+1)<<5)|((high?3:2)<<10));
                wallY[2*nw]=Math.max(y,height[i+32]);wallY[2*nw+1]=Math.min(y,height[i+32]);nw++;
            }
        }
        return new ReliefMesh(Arrays.copyOf(tops,nt),Arrays.copyOf(ys,nt),Arrays.copyOf(walls,nw),Arrays.copyOf(wallY,nw*2));
    }
    private static boolean same(int i,float y,float[] height,byte[] known,boolean[] used){
        return !used[i]&&known[i]!=0&&height[i]==y;
    }
    public record Segment(double x,double z,double length){}
    /** Clip a riser to one UV patch, assigning a shared edge to the higher-cell side. */
    public static Segment clipWall(int x,int z,int dir,double x0,double z0,double x1,double z1){
        if(dir<0||dir>3)throw new IllegalArgumentException("Direction");
        if(dir<2){
            double edge=x+(dir==1?1:0);
            if(dir==0?(edge<x0||edge>=x1):(edge<=x0||edge>x1))return null;
            double start=Math.max(z,z0),end=Math.min(z+1,z1);
            return end>start?new Segment(edge,start,end-start):null;
        }
        double edge=z+(dir==3?1:0);
        if(dir==2?(edge<z0||edge>=z1):(edge<=z0||edge>z1))return null;
        double start=Math.max(x,x0),end=Math.min(x+1,x1);
        return end>start?new Segment(start,edge,end-start):null;
    }
    /** Patch perimeter belongs exclusively to ReliefSeams, including clipped ancestor cells. */
    public static Segment clipInteriorWall(int x,int z,int dir,double x0,double z0,double x1,double z1){
        Segment s=clipWall(x,z,dir,x0,z0,x1,z1);
        return s!=null&&(dir<2?s.x()>x0&&s.x()<x1:s.z()>z0&&s.z()<z1)?s:null;
    }
    public int topCount(){return tops.length;}
    public int topX(int i){return tops[i]&63;}
    public int topZ(int i){return tops[i]>>>6&63;}
    public int topWidth(int i){return tops[i]>>>12&63;}
    public int topDepth(int i){return tops[i]>>>18&63;}
    public float topHeight(int i){return topY[i];}
    public int wallCount(){return walls.length;}
    public int wallX(int i){return walls[i]&31;}
    public int wallZ(int i){return walls[i]>>>5&31;}
    public int wallDirection(int i){return walls[i]>>>10&3;}
    public float wallTop(int i){return wallY[i*2];}
    public float wallBottom(int i){return wallY[i*2+1];}
}
