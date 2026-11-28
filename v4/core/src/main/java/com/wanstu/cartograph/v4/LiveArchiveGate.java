package com.wanstu.cartograph.v4;
import java.util.*;

/** Shared live/archive arbitration; no disk work under the monitor. */
public final class LiveArchiveGate {
    private record Region(int x,int z){}
    private final Set<ActiveChunkScheduler.Position> active=new HashSet<>();
    private final Map<Region,BitSet> liveCommitted=new HashMap<>();
    private final Map<ActiveChunkScheduler.Position,Boolean> reading=new HashMap<>();
    private final Set<ActiveChunkScheduler.Position> parked=new HashSet<>();
    private final Set<ActiveChunkScheduler.Position> wakeups=new LinkedHashSet<>();
    public synchronized void loaded(int x,int z){
        var p=new ActiveChunkScheduler.Position(x,z);
        active.add(p);if(reading.containsKey(p))reading.put(p,Boolean.FALSE);
    }
    public synchronized void unloaded(int x,int z){
        var p=new ActiveChunkScheduler.Position(x,z);
        active.remove(p);if(reading.containsKey(p))reading.put(p,Boolean.FALSE);
        if(parked.remove(p))wakeups.add(p);
    }
    /** Register the wait atomically with the active check, so unload cannot be lost. */
    synchronized boolean parkIfActive(ActiveChunkScheduler.Position p){
        if(!active.contains(p))return false;
        parked.add(p);return true;
    }
    /** A parked durable task became eligible; bypass only idle backoff, not global budgets. */
    public synchronized boolean hasWakeups(){return !wakeups.isEmpty();}
    synchronized List<ActiveChunkScheduler.Position> drainWakeups(int max){
        List<ActiveChunkScheduler.Position> result=new ArrayList<>();
        var it=wakeups.iterator();while(it.hasNext()&&result.size()<max){result.add(it.next());it.remove();}return result;
    }
    synchronized boolean begin(int x,int z){
        var p=new ActiveChunkScheduler.Position(x,z);
        if(active.contains(p))return false;
        if(reading.putIfAbsent(p,Boolean.TRUE)!=null)throw new IllegalStateException("Duplicate archive read");return true;
    }
    synchronized boolean valid(int x,int z){return Boolean.TRUE.equals(reading.get(new ActiveChunkScheduler.Position(x,z)));}
    synchronized void end(int x,int z){reading.remove(new ActiveChunkScheduler.Position(x,z));}
    synchronized void committed(int x,int z){
        var key=new Region(Math.floorDiv(x,32),Math.floorDiv(z,32));
        liveCommitted.computeIfAbsent(key,k->new BitSet(1024)).set(Math.floorMod(z,32)*32+Math.floorMod(x,32));
    }
    synchronized boolean protectedLive(int x,int z){
        var bits=liveCommitted.get(new Region(Math.floorDiv(x,32),Math.floorDiv(z,32)));
        return bits!=null&&bits.get(Math.floorMod(z,32)*32+Math.floorMod(x,32));
    }
}
