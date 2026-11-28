package com.wanstu.cartograph.v4;

import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.CapturePump.*;

/**
 * Per-dimension server-thread scheduler. Real chunk lifecycle events feed this
 * independently of players, tickets' cause, viewport or map-open state.
 * No world access, disk access or forced chunk loading in the scheduler.
 */
public final class ActiveChunkScheduler implements Source {
    public record Position(int x,int z){}
    public record InactiveDirty(Position position,long revision){}
    @FunctionalInterface public interface LoadedCapture {
        /** Must use a non-loading lookup; return null if no longer available. */
        Capture begin(Ticket ticket);
    }
    @FunctionalInterface public interface SavedHandoff {
        /** Nonblocking acceptance by the saved-world dirty index; false means retain for retry. */
        boolean offer(InactiveDirty dirty);
    }
    public record Metrics(int active,int queuedDirty,int queuedReview,int reserved,int inactivePending,
                          long dirtyStarts,long reviewStarts,long staleReceipts){}
    private static final class State {
        final Position pos;
        long revision,due,eligible;
        boolean dirty;
        Ticket ticket;
        long reservedRevision;
        State(Position pos,long revision){this.pos=pos;this.revision=revision;}
    }
    private static final Comparator<State> ORDER=Comparator.comparingLong((State s)->s.due)
        .thenComparingInt(s->s.pos.x).thenComparingInt(s->s.pos.z);
    private final Map<Position,State> active=new HashMap<>();
    private static final int CLEAN_LIMIT=8192;
    private final LinkedHashMap<Position,Boolean> cleanUnloaded=new LinkedHashMap<>();
    private final NavigableSet<State> dirtyQueue=new TreeSet<>(ORDER),reviewQueue=new TreeSet<>(ORDER);
    private final LinkedHashMap<Position,Long> inactive=new LinkedHashMap<>();
    private record Handoff(long revision,CompletableFuture<SavedDirtyIndex.Work> receipt){}
    private final Map<Position,Handoff> handoffs=new HashMap<>();
    private final Thread owner=Thread.currentThread();
    private final LoadedCapture factory;
    private final long reviewInterval,retryInterval;
    private final int dirtyBurst;
    private long now,sequence,dirtyStarts,reviewStarts,staleReceipts;
    private int consecutiveDirty,reserved;
    public ActiveChunkScheduler(LoadedCapture factory,long reviewInterval,long retryInterval,int dirtyBurst){
        this.factory=Objects.requireNonNull(factory);
        if(reviewInterval<1||retryInterval<1||dirtyBurst<1)throw new IllegalArgumentException("Intervals");
        this.reviewInterval=reviewInterval;this.retryInterval=retryInterval;this.dirtyBurst=dirtyBurst;
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Active chunk owner");}
    private long next(){return sequence=Math.incrementExact(sequence);}
    private static long plus(long a,long b){return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
    public void advance(long tick){owner();if(tick<now)throw new IllegalArgumentException("Tick moved backwards");now=tick;}
    private void removeQueued(State s){if(s.dirty)dirtyQueue.remove(s);else reviewQueue.remove(s);}
    private void schedule(State s,long due){
        if(s.ticket!=null)throw new IllegalStateException("Reserved");
        s.due=due;(s.dirty?dirtyQueue:reviewQueue).add(s);
    }
    /** Same-session verified clean unloads may skip immediate recapture; no persisted trust. */
    public void loadedObserved(int x,int z){owner();Position p=new Position(x,z);if(active.containsKey(p))return;loaded(x,z,!cleanUnloaded.containsKey(p));}
    public boolean isActive(int x,int z){owner();return active.containsKey(new Position(x,z));}
    /** needsCapture is true when fresh facts are missing or saved-world identity is not verified. */
    public void loaded(int x,int z,boolean needsCapture){
        owner();Position p=new Position(x,z);
        State existing=active.get(p);
        if(existing!=null){if(needsCapture)changed(x,z);return;}
        cleanUnloaded.remove(p);
        State s=new State(p,next());
        s.dirty=inactive.remove(p)!=null||needsCapture;
        handoffs.remove(p);
        active.put(p,s);
        // Spread clean-load reviews across the interval, not all on one tick.
        long jitter=Math.floorMod((long)p.hashCode(),reviewInterval);
        schedule(s,s.dirty?now:plus(now,plus(1,jitter)));
    }
    /** Relevant mutation, or conservative save/biome/mod fallback. No duplicate queued nodes. */
    public void changed(int x,int z){
        owner();Position p=new Position(x,z);State s=active.get(p);
        cleanUnloaded.remove(p);
        if(s==null){inactive.put(p,next());return;}
        if(s.ticket==null)removeQueued(s);
        s.revision=next();s.dirty=true;
        if(s.ticket==null)schedule(s,Math.max(now,s.eligible));
    }
    public void unloaded(int x,int z){
        owner();Position p=new Position(x,z);State s=active.remove(p);
        if(s==null)return;
        if(s.ticket==null)removeQueued(s);else reserved--;
        // An unfinished periodic review may have found a bypassed modification.
        if(s.dirty||s.ticket!=null)inactive.put(p,next());
        else{
            cleanUnloaded.put(p,Boolean.TRUE);
            while(cleanUnloaded.size()>CLEAN_LIMIT){var it=cleanUnloaded.keySet().iterator();it.next();it.remove();}
        }
    }
    /**
     * Shutdown phase, after lifecycle event intake and capture starts have stopped.
     * Preserve dirty and in-flight active work for the saved-world path. The caller
     * must drain durable handoff receipts before disposing this scheduler.
     */
    public void prepareShutdown(){
        owner();
        for(State s:active.values())if(s.dirty||s.ticket!=null)inactive.put(s.pos,next());
        active.clear();dirtyQueue.clear();reviewQueue.clear();cleanUnloaded.clear();reserved=0;
    }
    /** Bounded handoff. The receiver must retain work until the matching saved revision is processed. */
    public int drainInactive(int max,SavedHandoff handoff){
        owner();Objects.requireNonNull(handoff);if(max<0)throw new IllegalArgumentException("Limit");
        int accepted=0;
        var it=inactive.entrySet().iterator();
        while(it.hasNext()&&accepted<max){
            var e=it.next();
            if(!handoff.offer(new InactiveDirty(e.getKey(),e.getValue())))break;
            handoffs.remove(e.getKey());it.remove();accepted++;
        }
        return accepted;
    }
    /** Bounded asynchronous handoff. Do not remove a pending revision merely on queue acceptance.
     *  Rotate examined entries for fairness; a stale receipt cannot clear a newer revision.
     */
    public int drainInactiveAsync(int max,DirtyHandoffQueue queue){
        owner();Objects.requireNonNull(queue);if(max<0)throw new IllegalArgumentException("Limit");
        int examined=Math.min(max,inactive.size()),durable=0;
        for(int i=0;i<examined;i++){
            var it=inactive.entrySet().iterator();var e=it.next();
            Position p=e.getKey();long rev=e.getValue();it.remove();
            Handoff h=handoffs.get(p);
            if(h!=null&&h.receipt.isDone()){
                handoffs.remove(p);
                try{
                    var work=h.receipt.join();
                    if(h.revision==rev&&work.position().equals(p)){durable++;continue;}
                }catch(CompletionException|CancellationException failure){/* retry below */}
                h=null;
            }
            if(h==null){
                var receipt=queue.submit(new InactiveDirty(p,rev));
                if(!receipt.isCompletedExceptionally())handoffs.put(p,new Handoff(rev,receipt));
            }
            inactive.put(p,rev);
        }
        return durable;
    }
    private State due(NavigableSet<State> queue){
        return queue.isEmpty()||queue.first().due>now?null:queue.first();
    }
    @Override public Ticket poll(){
        owner();State urgent=due(dirtyQueue),review=due(reviewQueue);
        State s=review!=null&&(urgent==null||consecutiveDirty>=dirtyBurst)?review:urgent;
        if(s==null)return null;
        removeQueued(s);
        if(s.dirty){dirtyStarts++;if(consecutiveDirty<dirtyBurst)consecutiveDirty++;}
        else{reviewStarts++;consecutiveDirty=0;}
        s.ticket=new Ticket(s.pos.x,s.pos.z,next());s.reservedRevision=s.revision;reserved++;
        return s.ticket;
    }
    private State match(Ticket ticket){
        State s=active.get(new Position(ticket.chunkX(),ticket.chunkZ()));
        return s!=null&&ticket.equals(s.ticket)?s:null;
    }
    @Override public boolean current(Ticket ticket){
        owner();State s=match(ticket);return s!=null&&s.revision==s.reservedRevision;
    }
    @Override public Capture begin(Ticket ticket){
        owner();State s=match(ticket);
        if(s==null||s.revision!=s.reservedRevision)return null;
        Capture source=factory.begin(ticket);
        if(source==null)return null;
        return new Capture(){
            public boolean valid(){owner();return match(ticket)==s&&s.revision==s.reservedRevision&&source.valid();}
            public RegionPageStore.Entry column(int index){owner();return source.column(index);}
        };
    }
    @Override public void finish(Ticket ticket,Outcome outcome){
        owner();Objects.requireNonNull(outcome);State s=match(ticket);
        if(s==null){staleReceipts++;return;}
        s.ticket=null;reserved--;
        s.dirty=outcome!=Outcome.COMMITTED||s.revision!=s.reservedRevision;
        s.eligible=plus(now,retryInterval);
        schedule(s,s.dirty?s.eligible:plus(now,reviewInterval));
    }
    public Metrics metrics(){
        owner();return new Metrics(active.size(),dirtyQueue.size(),reviewQueue.size(),reserved,inactive.size(),dirtyStarts,reviewStarts,staleReceipts);
    }
}
