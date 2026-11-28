package com.wanstu.cartograph.v4;

/** Hysteresis on measured map CPU submission time, not a GPU timer or total game FPS. */
public final class MapWorkBudget {
    private int pressure,slow,quiet;
    public int pressure(){return pressure;}
    public int textureUploads(){return pressure==0?4:pressure==1?2:1;}
    public int meshUploads(){return pressure==0?2:1;}
    public long sliceNanos(){return pressure==0?1_000_000:pressure==1?750_000:500_000;}
    public boolean prefetchNeighbors(){return pressure==0;}
    public void sample(long nanos){
        if(nanos<0)throw new IllegalArgumentException("Elapsed work");
        if(nanos>=4_000_000){
            quiet=0;
            if(++slow>=3){pressure=Math.min(2,pressure+1);slow=0;}
        }else if(nanos<=1_500_000){
            slow=0;
            if(++quiet>=60){pressure=Math.max(0,pressure-1);quiet=0;}
        }else{slow=quiet=0;}
    }
}
