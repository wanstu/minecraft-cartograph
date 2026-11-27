package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** Disk-first reference implementation; use one serialized IO worker, never the render thread. */
public final class DiskGeographicPyramid {
    private final RegionPageStore store;
    private final int maxLod;
    public DiskGeographicPyramid(RegionPageStore store,int maxLod){
        if(maxLod<0||maxLod>10)throw new IllegalArgumentException("LOD");
        this.store=Objects.requireNonNull(store);this.maxLod=maxLod;
    }
    public synchronized Entry get(GeographicPyramid.Cell cell)throws IOException{
        if(cell.level()<0||cell.level()>maxLod)throw new IllegalArgumentException("LOD");
        return store.read(Key.of(cell)).entries().get(Key.slot(cell));
    }
    /** Single observed pixel edit; null removes coverage. */
    public synchronized int update(int x,int z,Entry leaf)throws IOException{
        Map<GeographicPyramid.Cell,Entry> edits=new HashMap<>();
        edits.put(new GeographicPyramid.Cell(0,x,z),leaf);
        return apply(edits);
    }
    /** Full 16x16 observed chunk snapshot. Null entries represent unknown columns. */
    public synchronized int updateChunk(int chunkX,int chunkZ,Entry[] columns)throws IOException{
        Objects.requireNonNull(columns);
        if(columns.length!=256)throw new IllegalArgumentException("Expected 256 columns");
        int x=Math.multiplyExact(chunkX,16),z=Math.multiplyExact(chunkZ,16);
        Map<GeographicPyramid.Cell,Entry> edits=new HashMap<>();
        for(int dz=0;dz<16;dz++)for(int dx=0;dx<16;dx++)
            edits.put(new GeographicPyramid.Cell(0,x+dx,z+dz),columns[dz*16+dx]);
        return apply(edits);
    }
    private int apply(Map<GeographicPyramid.Cell,Entry> proposed)throws IOException{
        Map<GeographicPyramid.Cell,Entry> changes=new HashMap<>();
        for(var e:proposed.entrySet()){
            if(e.getValue()!=null&&e.getValue().feature()==null)throw new IllegalArgumentException("Leaf feature");
            if(!Objects.equals(get(e.getKey()),e.getValue()))changes.put(e.getKey(),e.getValue());
        }
        if(changes.isEmpty())return 0;
        List<Page> writes=new ArrayList<>();
        for(int level=0;level<=maxLod&&!changes.isEmpty();level++){
            // An aligned chunk occupies at most one page at every level.
            Map<Key,Map<Integer,Entry>> modified=new HashMap<>();
            Map<Key,Page> originals=new HashMap<>();
            for(var e:changes.entrySet()){
                Key key=Key.of(e.getKey());
                if(!originals.containsKey(key)){
                    Page old=store.read(key);
                    originals.put(key,old);modified.put(key,new HashMap<>(old.entries()));
                }
                var entries=modified.get(key);int slot=Key.slot(e.getKey());
                if(e.getValue()==null)entries.remove(slot);else entries.put(slot,e.getValue());
            }
            for(var e:modified.entrySet()){
                Page old=originals.get(e.getKey());
                writes.add(new Page(old.key(),Math.addExact(old.revision(),1),e.getValue()));
            }
            if(level==maxLod)break;
            Set<GeographicPyramid.Cell> parents=new HashSet<>();
            for(var cell:changes.keySet())parents.add(cell.parent());
            Map<GeographicPyramid.Cell,Entry> next=new HashMap<>();
            for(var parent:parents){
                List<Aggregate> children=new ArrayList<>(4);
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    var child=new GeographicPyramid.Cell(level,parent.x()*2+dx,parent.z()*2+dz);
                    Entry e=changes.containsKey(child)?changes.get(child):get(child);
                    if(e!=null)children.add(e.stats());
                }
                Aggregate aggregate=Aggregate.merge(children);
                Entry value=aggregate==null?null:new Entry(aggregate,null);
                if(!Objects.equals(get(parent),value))next.put(parent,value);
            }
            changes=next;
        }
        store.writeBatch(writes);
        return writes.size();
    }
}
