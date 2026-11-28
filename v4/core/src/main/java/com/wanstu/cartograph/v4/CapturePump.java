package com.wanstu.cartograph.v4;

import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Entry;

/**
 * Tick-owned capture pump, independent of map UI and viewport.
 * Source owns the dirty/discovery index and must only acknowledge the matching revision.
 * Its poll/begin/read methods must not load a Minecraft chunk or perform blocking disk IO.
 */
public final class CapturePump {
    public record Ticket(int chunkX,int chunkZ,long revision) {}
    public enum Outcome { COMMITTED, RETRY }
    public interface Source {
        /** Return a bounded amount of work; reserve the ticket until finish. */
        Ticket poll();
        /** Null means not currently available. Source must arrange a later retry/saved-chunk path. */
        Capture begin(Ticket ticket);
        /** Rejected submissions may retry only while the original reservation is current. */
        default boolean current(Ticket ticket){return true;}
        /** RETRY is not completion; requeue without a tight retry loop. */
        void finish(Ticket ticket,Outcome outcome);
    }
    public interface Capture {
        /** May return null for an observed unknown column. Must be a bounded operation. */
        Entry column(int index);
        boolean valid();
    }
    @FunctionalInterface public interface Sink {
        CompletableFuture<GeographicWorker.WriteResult> submit(int x,int z,Entry[] columns);
    }
    public record Metrics(long sampledColumns,long committed,long retries,int active,int outstanding) {}
    private static final class Task {
        final Ticket ticket;
        Capture capture;
        final Entry[] columns=new Entry[256];
        int next;
        CompletableFuture<GeographicWorker.WriteResult> receipt;
        Task(Ticket ticket,Capture capture){this.ticket=ticket;this.capture=capture;}
    }
    private final Source source;
    private final Sink sink;
    private final int maxOutstanding;
    private final ArrayDeque<Task> outstanding=new ArrayDeque<>();
    private final Thread owner=Thread.currentThread();
    private Task active;
    private long sampled,committed,retries;

    public CapturePump(Source source,Sink sink,int maxOutstanding){
        this.source=Objects.requireNonNull(source);this.sink=Objects.requireNonNull(sink);
        if(maxOutstanding<1)throw new IllegalArgumentException("outstanding");
        this.maxOutstanding=maxOutstanding;
    }
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("capture owner thread");}
    public Metrics metrics(){checkOwner();return new Metrics(sampled,committed,retries,active==null?0:1,outstanding.size());}
    private void finish(Ticket ticket,Outcome outcome){
        source.finish(ticket,outcome);
        if(outcome==Outcome.COMMITTED)committed++;else retries++;
    }
    /** Hard work-count cap and cooperative time cap (one source operation may overrun). */
    public int tick(long budgetNanos,int maxColumns,int maxStarts){
        checkOwner();
        if(budgetNanos<=0||maxColumns<0||maxStarts<0)throw new IllegalArgumentException("tick budget");
        long started=System.nanoTime();
        int columns=0,starts=0;
        // Poll only bounded in-flight receipts; no blocking wait on the server thread.
        var it=outstanding.iterator();
        while(it.hasNext()&&System.nanoTime()-started<budgetNanos){
            Task t=it.next();if(!t.receipt.isDone())continue;
            Outcome result;
            try{result=t.receipt.join()==GeographicWorker.WriteResult.COMMITTED?Outcome.COMMITTED:Outcome.RETRY;}
            catch(CompletionException e){
                if(e.getCause() instanceof RejectedExecutionException){
                    // Do not submit old columns after a mutation or an unload/reload.
                    if(!source.current(t.ticket)){it.remove();finish(t.ticket,Outcome.RETRY);continue;}
                    if(maxStarts==0)continue; // receipt-only mode does not add IO work
                    // Keep the captured columns, retry submission only once per tick.
                    try{t.receipt=Objects.requireNonNull(sink.submit(t.ticket.chunkX,t.ticket.chunkZ,t.columns));}
                    catch(RuntimeException failure){it.remove();finish(t.ticket,Outcome.RETRY);}
                    continue;
                }
                result=Outcome.RETRY;
            }catch(CancellationException e){result=Outcome.RETRY;}
            it.remove();finish(t.ticket,result);
        }
        Task checked=null;
        while(columns<maxColumns&&System.nanoTime()-started<budgetNanos){
            if(outstanding.size()>=maxOutstanding)break;
            if(active==null){
                if(starts>=maxStarts)break;
                Ticket ticket=source.poll();if(ticket==null)break;
                starts++;
                Capture capture;
                try{capture=source.begin(ticket);}catch(RuntimeException e){finish(ticket,Outcome.RETRY);continue;}
                if(capture==null){finish(ticket,Outcome.RETRY);continue;}
                active=new Task(ticket,capture);
            }
            Task t=active;
            try{
                if(checked!=t){
                    if(!t.capture.valid()){active=null;finish(t.ticket,Outcome.RETRY);continue;}
                    checked=t;
                }
                t.columns[t.next]=t.capture.column(t.next);
                t.next++;sampled++;columns++;
                if(t.next==256){
                    active=null;
                    if(!t.capture.valid()){finish(t.ticket,Outcome.RETRY);continue;}
                    t.capture=null; // never retain a loaded chunk while waiting for disk
                    t.receipt=Objects.requireNonNull(sink.submit(t.ticket.chunkX,t.ticket.chunkZ,t.columns));
                    outstanding.add(t);
                }
            }catch(RuntimeException e){active=null;finish(t.ticket,Outcome.RETRY);}
        }
        return columns;
    }
    /** Stop sampling on unload; completed captures already submitted stay with the worker. */
    public void abandonPartial(){
        checkOwner();if(active!=null){finish(active.ticket,Outcome.RETRY);active=null;}
    }
}
