package com.wanstu.cartograph.v4;

import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** Bounded per-dimension invalidation journal. A lagging reader receives a reset, never a false hit. */
public final class PresentationChanges {
    private static final int LIMIT=8192;
    private record Event(long sequence,Key key){}
    public record Delta(long sequence,boolean reset,Set<Key> keys){
        public Delta{keys=Set.copyOf(keys);}
    }
    private final ArrayDeque<Event> events=new ArrayDeque<>();
    private long sequence,floor;
    synchronized void changed(Collection<Key> keys){
        for(Key key:keys){
            long next=sequence=Math.incrementExact(sequence);
            events.addLast(new Event(next,key));
            if(events.size()>LIMIT)floor=events.removeFirst().sequence;
        }
    }
    synchronized void reset(){floor=sequence=Math.incrementExact(sequence);events.clear();}
    public synchronized long sequence(){return sequence;}
    public synchronized Delta since(long previous){
        if(previous<floor||previous>sequence)return new Delta(sequence,true,Set.of());
        if(previous==sequence)return new Delta(sequence,false,Set.of());
        Set<Key> keys=new HashSet<>();
        for(var it=events.descendingIterator();it.hasNext();){var event=it.next();if(event.sequence<=previous)break;keys.add(event.key);}
        return new Delta(sequence,false,keys);
    }
}
