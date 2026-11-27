package com.wanstu.cartograph.v4;

import java.util.*;

/** Render-owner cache of immutable dependency groups with a bounded resident weight.
 * Never returns a resource built for a different dependency key.
 */
public final class RetainedGroups<K,V extends AutoCloseable> implements AutoCloseable {
    public record Item<V>(V value,long bytes){}
    @FunctionalInterface public interface Builder<K,V>{Item<V> build(K key);}
    private final LinkedHashMap<K,Item<V>> entries=new LinkedHashMap<>(16,.75f,true);
    private final long limit;
    private final int countLimit;
    private long bytes,builds;
    public RetainedGroups(long limit,int countLimit){
        if(limit<1||countLimit<1)throw new IllegalArgumentException("Cache budget");
        this.limit=limit;this.countLimit=countLimit;
    }
    public V get(K key){var item=entries.get(key);return item==null?null:item.value;}
    public boolean ensure(K key,long estimate,Set<K> protectedKeys,Builder<K,V> builder){
        if(entries.containsKey(key)){entries.get(key);return true;}
        if(estimate<0||estimate>limit)return false;
        while(bytes>limit-estimate||entries.size()>=countLimit){
            var it=entries.entrySet().iterator();boolean removed=false;
            while(it.hasNext()){
                var e=it.next();if(protectedKeys.contains(e.getKey()))continue;
                bytes-=e.getValue().bytes;dispose(e.getValue().value);it.remove();removed=true;break;
            }
            if(!removed)return false;
        }
        var item=Objects.requireNonNull(builder.build(key));
        if(item.bytes<0||item.bytes>estimate){dispose(item.value);throw new IllegalStateException("Resource exceeded estimate");}
        entries.put(key,item);bytes+=item.bytes;builds++;return true;
    }
    public void retainValid(java.util.function.Predicate<K> valid){
        var it=entries.entrySet().iterator();
        while(it.hasNext()){
            var e=it.next();if(valid.test(e.getKey()))continue;
            bytes-=e.getValue().bytes;dispose(e.getValue().value);it.remove();
        }
    }
    public long bytes(){return bytes;}
    public int size(){return entries.size();}
    public long builds(){return builds;}
    private static void dispose(AutoCloseable value){
        try{value.close();}catch(Exception e){throw new IllegalStateException("Resource close",e);}
    }
    @Override public void close(){for(var item:entries.values())dispose(item.value);entries.clear();bytes=0;}
}
