package com.wanstu.cartograph.v4;
import java.util.*;
public final class ReliefMeshTest{
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    public static void main(String[] args){
        float[] y=new float[1024];byte[] k=new byte[1024];Arrays.fill(y,70);Arrays.fill(k,(byte)255);
        var flat=ReliefMesh.build(y,k);
        check(flat.topCount()==1&&flat.topWidth(0)==32&&flat.topDepth(0)==32&&flat.wallCount()==0,"flat one quad");
        Random random=new Random(27);
        for(int trial=0;trial<100;trial++){
            for(int i=0;i<1024;i++){y[i]=random.nextInt(6)+64;k[i]=(byte)(random.nextInt(5)==0?0:255);}
            var mesh=ReliefMesh.build(y,k);int[] count=new int[1024];int expectedWalls=0;
            for(int j=0;j<mesh.topCount();j++)for(int z=mesh.topZ(j);z<mesh.topZ(j)+mesh.topDepth(j);z++)
                for(int x=mesh.topX(j);x<mesh.topX(j)+mesh.topWidth(j);x++){
                    int i=z*32+x;count[i]++;check(mesh.topHeight(j)==y[i],"exact height");
                }
            for(int i=0;i<1024;i++){
                check(count[i]==(k[i]==0?0:1),"exact nonoverlapping top coverage");
                if(k[i]==0)continue;
                if(i%32<31&&k[i+1]!=0&&y[i]!=y[i+1])expectedWalls++;
                if(i/32<31&&k[i+32]!=0&&y[i]!=y[i+32])expectedWalls++;
            }
            check(mesh.wallCount()==expectedWalls,"one riser per observed height difference");
            Set<String> edges=new HashSet<>();
            for(int j=0;j<mesh.wallCount();j++){
                int x=mesh.wallX(j),z=mesh.wallZ(j),dir=mesh.wallDirection(j);
                int i=z*32+x,other=i+new int[]{-1,1,-32,32}[dir];
                check(k[i]!=0&&k[other]!=0&&mesh.wallTop(j)==y[i]&&mesh.wallBottom(j)==y[other]&&y[i]>y[other],"riser orientation");
                check(edges.add(Math.min(i,other)+":"+Math.max(i,other)),"unique edge");
            }
        }
        // For every sub-cell fallback division, clipped wall lengths sum to one,
        // never extend outside their patch, and belong only to the high-cell side.
        for(int dir=0;dir<4;dir++)for(int factor:new int[]{1,2,4,8,16}){
            double sum=0;
            for(int z=0;z<3*factor;z++)for(int x=0;x<3*factor;x++){
                double x0=(double)x/factor,z0=(double)z/factor,x1=x0+1.0/factor,z1=z0+1.0/factor;
                var seg=ReliefMesh.clipWall(1,1,dir,x0,z0,x1,z1);
                if(seg==null)continue;
                double ex=seg.x()+(dir>=2?seg.length():0),ez=seg.z()+(dir<2?seg.length():0);
                check(seg.x()>=x0&&seg.z()>=z0&&ex<=x1&&ez<=z1,"wall clipped to target");
                sum+=seg.length();
            }
            check(sum==1,"unique full wall coverage");
        }
        for(int dir=0;dir<4;dir++){
            check(ReliefMesh.clipInteriorWall(1,1,dir,0,0,3,3)!=null,"interior wall remains");
            check(ReliefMesh.clipInteriorWall(1,1,dir,1,1,2,2)==null,"perimeter wall belongs only to seam planner");
        }
        System.out.println("PASS: flat page one quad, 100 randomized relief pages, exact coverage/heights, no duplicate or unknown walls, subcell fallback clipping");
    }
}
