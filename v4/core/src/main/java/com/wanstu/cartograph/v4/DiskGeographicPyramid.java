package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** Bounded page-local working arrays; no per-pixel spatial keys or hash lookups. */
public final class DiskGeographicPyramid {
    private final RegionPageStore store;
    private final int maxLod;
    private static final int SLOTS=EDGE*EDGE;
    public DiskGeographicPyramid(RegionPageStore store,int maxLod){
        if(maxLod<0||maxLod>10)throw new IllegalArgumentException("LOD");
        this.store=Objects.requireNonNull(store);this.maxLod=maxLod;
    }
    public synchronized Entry get(GeographicPyramid.Cell cell)throws IOException{
        if(cell.level()<0||cell.level()>maxLod)throw new IllegalArgumentException("LOD");
        return store.read(Key.of(cell)).entries().get(Key.slot(cell));
    }
    private static final class WorkPage {
        final Page old;
        final Entry[] data=new Entry[SLOTS];
        final BitSet changed=new BitSet(SLOTS);
        WorkPage(Page old){
            this.old=old;
            old.entries().forEach((slot,e)->data[slot]=e);
        }
        void put(int slot,Entry entry){
            data[slot]=entry;
            if(Objects.equals(old.entries().get(slot),entry))changed.clear(slot);
            else changed.set(slot);
        }
        Page publish(){
            Map<Integer,Entry> entries=new HashMap<>(SLOTS*4/3+1);
            for(int i=0;i<SLOTS;i++)if(data[i]!=null)entries.put(i,data[i]);
            return new Page(old.key(),Math.addExact(old.revision(),1),entries);
        }
    }
    private WorkPage page(Map<Key,WorkPage> pages,Key key)throws IOException{
        WorkPage p=pages.get(key);
        if(p==null){p=new WorkPage(store.read(key));pages.put(key,p);}
        return p;
    }
    private static void validateLeaf(Entry e){
        if(e!=null&&e.feature()==null)throw new IllegalArgumentException("Leaf feature");
    }
    public synchronized int update(int x,int z,Entry leaf)throws IOException{
        validateLeaf(leaf);
        Map<Key,WorkPage> pages=new HashMap<>();
        var cell=new GeographicPyramid.Cell(0,x,z);
        page(pages,Key.of(cell)).put(Key.slot(cell),leaf);
        return apply(pages);
    }
    public static final int MAX_BATCH_CHUNKS=64;
    public record ChunkSnapshot(int x,int z,Entry[] columns){
        public ChunkSnapshot{
            Objects.requireNonNull(columns);
            if(columns.length!=256)throw new IllegalArgumentException("Expected 256 columns");
            Math.multiplyExact(x,16);Math.multiplyExact(z,16);
            columns=columns.clone();
        }
        @Override public Entry[] columns(){return columns.clone();}
    }
    public synchronized int updateChunk(int chunkX,int chunkZ,Entry[] columns)throws IOException{
        return updateChunks(List.of(new ChunkSnapshot(chunkX,chunkZ,columns)));
    }
    public synchronized int updateChunks(List<ChunkSnapshot> chunks)throws IOException{
        if(chunks.size()>MAX_BATCH_CHUNKS)throw new IllegalArgumentException("Chunk batch limit");
        Map<Key,WorkPage> pages=new HashMap<>();
        for(var snapshot:chunks){
            int x=Math.multiplyExact(snapshot.x,16),z=Math.multiplyExact(snapshot.z,16);
            WorkPage p=page(pages,new Key(0,Math.floorDiv(x,EDGE),Math.floorDiv(z,EDGE)));
            int startX=Math.floorMod(x,EDGE),startZ=Math.floorMod(z,EDGE);
            for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++){
                Entry e=snapshot.columns[dz*16+dx];validateLeaf(e);
                p.put((startZ+dz)*EDGE+startX+dx,e);
            }
        }
        return apply(pages);
    }
    private int apply(Map<Key,WorkPage> pages)throws IOException{
        List<Page> writes=new ArrayList<>();
        BitSet parents=new BitSet(SLOTS/4);
        ArrayList<Aggregate> children=new ArrayList<>(4);
        for(int level=0;level<=maxLod&&!pages.isEmpty();level++){
            Map<Key,WorkPage> next=new HashMap<>();
            for(WorkPage p:pages.values()){
                if(p.changed.isEmpty())continue;
                writes.add(p.publish());
                if(level==maxLod)continue;
                // Every aligned 2x2 child group is wholly inside a single 32x32 page.
                Key key=p.old.key();
                WorkPage parent=page(next,new Key(level+1,Math.floorDiv(key.x(),2),Math.floorDiv(key.z(),2)));
                int half=EDGE/2,offsetX=Math.floorMod(key.x(),2)*half,offsetZ=Math.floorMod(key.z(),2)*half;
                parents.clear();
                for(int i=p.changed.nextSetBit(0);i>=0;i=p.changed.nextSetBit(i+1))
                    parents.set((i/EDGE/2)*half+(i%EDGE/2));
                for(int i=parents.nextSetBit(0);i>=0;i=parents.nextSetBit(i+1)){
                    int x=i%half,z=i/half,slot=(z*2)*EDGE+x*2;
                    children.clear();
                    for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                        Entry e=p.data[slot+dz*EDGE+dx];if(e!=null)children.add(e.stats());
                    }
                    Aggregate a=Aggregate.merge(children);
                    parent.put((offsetZ+z)*EDGE+offsetX+x,a==null?null:new Entry(a,null));
                }
            }
            pages=next;
        }
        store.writeBatch(writes);
        return writes.size();
    }
}
