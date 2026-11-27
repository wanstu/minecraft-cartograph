package com.wanstu.cartograph.v4.neoforge;

import com.wanstu.cartograph.v4.MapPreferences;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client-thread scheduling; one small atomic write on a dedicated worker after changes settle. */
final class V4ClientPreferences {
    private static final Logger LOG=LoggerFactory.getLogger(V4ClientPreferences.class);
    private final Path path;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{
        var thread=new Thread(r,"Cartograph V4 preferences");thread.setDaemon(true);return thread;
    });
    private MapPreferences desired,saved,writing;
    private Future<?> pending;
    private long tick,notBefore;
    private boolean closed;
    V4ClientPreferences(Path path){
        this.path=path;
        try{desired=MapPreferences.read(path);}
        catch(Exception ex){LOG.warn("Cartograph V4 preferences unreadable; using defaults without overwriting",ex);desired=MapPreferences.defaults();}
        saved=desired;
    }
    MapPreferences current(){return desired;}
    void update(MapPreferences next){
        if(closed||next.equals(desired))return;
        desired=next;notBefore=tick+10;
    }
    void tick(){
        if(closed)return;
        tick++;
        if(pending!=null&&pending.isDone()){
            try{pending.get();saved=writing;}
            catch(ExecutionException ex){LOG.warn("Cartograph V4 preference write failed",ex.getCause());notBefore=tick+100;}
            catch(InterruptedException ex){Thread.currentThread().interrupt();notBefore=tick+100;}
            finally{pending=null;writing=null;}
        }
        if(pending==null&&!desired.equals(saved)&&tick>=notBefore){
            writing=desired;var snapshot=writing;
            pending=worker.submit(()->{snapshot.write(path);return null;});
        }
    }
    void expedite(){notBefore=tick;}
    void close(){
        if(closed)return;
        closed=true;
        // At shutdown, submit at most one final snapshot behind the current write.
        var last=desired;var previous=pending;var previousValue=writing;var persisted=saved;
        Future<?> finalWrite=worker.submit(()->{
            MapPreferences durable=persisted;
            if(previous!=null)try{previous.get();durable=previousValue;}catch(ExecutionException ex){LOG.warn("Cartograph V4 previous preference write failed",ex.getCause());}
            if(!last.equals(durable))last.write(path);
            return null;
        });
        worker.shutdown();
        try{finalWrite.get(2,TimeUnit.SECONDS);}
        catch(InterruptedException ex){Thread.currentThread().interrupt();LOG.warn("Cartograph V4 final preference save interrupted",ex);}
        catch(ExecutionException|TimeoutException ex){LOG.warn("Cartograph V4 final preference save did not complete",ex);}
        finally{if(!worker.isTerminated())worker.shutdownNow();}
    }
}
