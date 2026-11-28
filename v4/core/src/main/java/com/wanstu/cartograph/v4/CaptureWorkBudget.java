package com.wanstu.cartograph.v4;

/** Server tick load and geographic queue backpressure; no world access or thread waits. */
public final class CaptureWorkBudget {
    private int level,slow,quiet;
    public int level(){return level;}
    public int columns(){return level==0?256:level==1?128:64;}
    public long nanos(){return level==0?750_000:level==1?500_000:250_000;}
    public int archiveBatch(){return level==0?16:level==1?4:1;}
    public int archivePeriod(){return level==0?2:level==1?10:40;}
    public boolean archiveDue(long tick,int queued){return queued<=(level==0?8:level==1?2:0)&&Math.floorMod(tick,archivePeriod())==0;}
    public boolean maintenanceAllowed(){return level==0;}
    /** Samples measured tick work (not inter-tick sleep), and the bounded world IO queue. */
    public void sample(long tickNanos,int queued){
        if(tickNanos<0||queued<0)throw new IllegalArgumentException("Load sample");
        if(queued>=32){level=2;slow=quiet=0;return;}
        if(tickNanos>=45_000_000||queued>=8){
            quiet=0;if(++slow>=3){level=Math.min(2,level+1);slow=0;}
        }else if(tickNanos<=30_000_000&&queued<=2){
            slow=0;if(++quiet>=100){level=Math.max(0,level-1);quiet=0;}
        }else{slow=quiet=0;}
    }
}
