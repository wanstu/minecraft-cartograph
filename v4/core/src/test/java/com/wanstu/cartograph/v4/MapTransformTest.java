package com.wanstu.cartograph.v4;
import java.util.Random;
public final class MapTransformTest {
    public static void main(String[] args){
        Random r=new Random(27);
        for(int i=0;i<10000;i++){
            double x=r.nextInt(-29_999_999,30_000_000),z=r.nextInt(-29_999_999,30_000_000);
            boolean raised=i%3!=0;
            var cam=new MapCamera(x,70,z,.003+r.nextDouble()*16,r.nextDouble()*360,raised?30+r.nextDouble()*59:90);
            double ox=Math.floor(x/1024)*1024,oz=Math.floor(z/1024)*1024,oy=0;
            double lx=r.nextDouble()*4096-2048,ly=r.nextDouble()*384-64,lz=r.nextDouble()*4096-2048;
            double d=.005;var m=MapTransform.coefficients(cam,ox,oy,oz,960,540,d,raised);
            var p=cam.project(new MapCamera.World(ox+lx,oy+ly,oz+lz));
            double[] expected={480+p.x(),270+p.y(),raised?100+p.depth()*d:0};
            for(int j=0;j<3;j++){
                double actual=m[j*4]*lx+m[j*4+1]*ly+m[j*4+2]*lz+m[j*4+3];
                if(Math.abs(actual-expected[j])>1e-6)throw new AssertionError("Affine mismatch");
            }
        }
        System.out.println("PASS: 10000 local-origin affine camera projections, including world-border and flat/relief");
    }
}
