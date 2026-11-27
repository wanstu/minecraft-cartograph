package com.wanstu.cartograph.v4;

import java.util.Random;
import static com.wanstu.cartograph.v4.FeatureDictionary.Terrain.*;
import static com.wanstu.cartograph.v4.GeographicPyramid.*;

public final class FoundationTest {
    private static void check(boolean test, String description) {
        if (!test) throw new AssertionError(description);
    }

    public static void main(String[] args) {
        FeatureDictionary d = new FeatureDictionary();
        var grass = new FeatureDictionary.Feature("Aa", "plains", GRASS);
        var collision = new FeatureDictionary.Feature("BB", "plains", GRASS);
        check(grass.hashCode() == collision.hashCode(), "collision fixture");
        int g = d.intern(grass);
        check(d.intern(grass) == g && d.intern(collision) != g, "safe deduplication");
        int w = d.intern(new FeatureDictionary.Feature("water", "river", WATER));
        GeographicPyramid p = new GeographicPyramid(10, d);
        Sample a = new Sample(g, 100, 90, 0, 100, 200, 300);
        Sample b = new Sample(w, 62, 50, 12, 400, 500, 600);
        Sample c = new Sample(g, UNKNOWN_HEIGHT, UNKNOWN_HEIGHT, 0, 700, 800, 900);
        check(p.update(-1, -1, a) == 11, "one ancestor per level");
        check(p.update(-2, -2, b) == 11, "negative aligned");
        p.update(-3, -3, c);
        check(p.update(-1, -1, a) == 0, "unchanged is no-op");
        Stats s = p.at(new Cell(2, -1, -1));
        check(s.known() == 3 && p.capacity(new Cell(2,-1,-1)) == 16, "partial coverage");
        check(s.groundCount() == 2 && s.groundSum() == 140 && s.surfaceSum() == 162, "exact weighted heights");
        check(s.minGround() == 50 && s.maxGround() == 90 && s.redSum() == 1200, "extrema and RGB");
        check(s.count(GRASS) == 2 && s.count(WATER) == 1 && s.wetCount() == 1 && s.depthSum() == 12, "semantic and water");
        p.update(-2,-2,null);
        s = p.at(new Cell(2,-1,-1));
        check(s.known() == 2 && s.minGround() == 90 && s.maxGround() == 90 && s.wetCount() == 0, "removal");
        p.update(-1,-1,null); p.update(-3,-3,null);
        check(p.at(new Cell(10,-1,-1)) == null, "unknown root not synthesized");

        // Deterministic randomized direct-scan oracle for a 64x64 region crossing chunk boundaries.
        Random r = new Random(20261127);
        Sample[][] raw = new Sample[64][64];
        for (int i=0;i<3000;i++) {
            int x=r.nextInt(64), z=r.nextInt(64);
            Sample v = r.nextInt(4)==0 ? null : new Sample(r.nextBoolean()?g:w,
                r.nextInt(300)-64, r.nextInt(5)==0?UNKNOWN_HEIGHT:r.nextInt(300)-64,
                r.nextInt(20), r.nextInt(65536), r.nextInt(65536), r.nextInt(65536));
            raw[z][x]=v; p.update(x,z,v);
        }
        int cells=0;
        for(int l=0;l<=6;l++){
            int n=1<<l;
            for(int z=0;z<64;z+=n) for(int x=0;x<64;x+=n){
                long known=0, gc=0, gs=0, sc=0, ss=0, red=0, green=0, blue=0, wet=0, depth=0, grasses=0;
                int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
                for(int dz=0;dz<n;dz++)for(int dx=0;dx<n;dx++){
                    Sample v=raw[z+dz][x+dx]; if(v==null)continue;
                    known++; red+=v.red();green+=v.green();blue+=v.blue(); if(v.featureId()==g)grasses++;
                    if(v.surfaceY()!=UNKNOWN_HEIGHT){sc++;ss+=v.surfaceY();}
                    if(v.groundY()!=UNKNOWN_HEIGHT){gc++;gs+=v.groundY();min=Math.min(min,v.groundY());max=Math.max(max,v.groundY());}
                    if(v.waterDepth()>0){wet++;depth+=v.waterDepth();}
                }
                Stats t=p.at(new Cell(l,x/n,z/n)); cells++;
                if(known==0){check(t==null,"unknown cell");continue;}
                check(t!=null && t.known()==known && t.groundCount()==gc && t.groundSum()==gs &&
                    t.surfaceCount()==sc && t.surfaceSum()==ss && t.redSum()==red && t.greenSum()==green &&
                    t.blueSum()==blue && t.wetCount()==wet && t.depthSum()==depth &&
                    t.count(GRASS)==grasses && t.count(WATER)==known-grasses &&
                    t.minGround()==(gc==0?UNKNOWN_HEIGHT:min) && t.maxGround()==(gc==0?UNKNOWN_HEIGHT:max),
                    "oracle LOD "+l+" "+x+","+z);
            }
        }
        System.out.println("PASS: dictionary collision, partial coverage, negative grid, local updates, removal; 3000 random edits; "+cells+" direct-scan LOD comparisons");
    }
}
