package com.wanstu.cartograph.v4;

/** Server-thread archive pacing. Wake signals survive an in-flight archive receipt. */
public final class ArchiveBackoff {
    private long next,earliest;
    private boolean wake;
    public void wake(){wake=true;}
    public boolean due(long tick,boolean parkedWake){return tick>=earliest&&(tick>=next||wake||parkedWake);}
    public void submitted(long tick){wake=false;earliest=tick+2;next=earliest;}
    public void completed(long tick,boolean discovered,boolean runnable,boolean progress,boolean sourceError){
        if(discovered)next=tick+(runnable&&progress?2:100);
        else if(sourceError&&!progress)next=tick+20;
    }
    /** Rejected or failed operations retain backoff even with queued wakeups. */
    public void failed(long tick){earliest=next=tick+200;}
}
