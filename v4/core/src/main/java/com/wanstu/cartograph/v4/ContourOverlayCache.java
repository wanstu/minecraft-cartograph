package com.wanstu.cartograph.v4;

import java.util.*;
import java.util.concurrent.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;

/** One bounded asynchronous contour job; all cache decisions belong to the render thread.
 * Results use compact local coordinates and exact raster dependency identity.
 */
public final class ContourOverlayCache implements AutoCloseable {
    public static final int MAX_VISIBLE=64,MAX_RESIDENT=96,MAX_SEGMENTS=4096;
    private static final long MAX_BYTES=12L*1024*1024;
    public record Input(MapRaster center,MapRaster east,MapRaster south,MapRaster southeast){
        public Input{Objects.requireNonNull(center);}
        public Key key(){return center.key();}
    }
    public static final class Lines {
        private final Input input;
        private final float[] data,draped;
        private final boolean limited;
        private Lines(Input input,float[] data,float[] draped,boolean limited){this.input=input;this.data=data;this.draped=draped;this.limited=limited;}
        public Input input(){return input;}
        public int size(){return data.length/5;}
        public float value(int segment,int channel){return data[segment*5+channel];}
        public int drapedSize(){return draped.length/6;}
        public float drapedValue(int segment,int channel){return draped[segment*6+channel];}
        public boolean limited(){return limited;}
        long bytes(){return 4L*(data.length+draped.length);}
    }
    public record Snapshot(List<Lines> ready,int requested,int pending,boolean limited,long builds){
        public Snapshot{ready=List.copyOf(ready);}
    }
    private final Executor executor;
    private final ExecutorService owned;
    private final LinkedHashMap<Input,Lines> cache=new LinkedHashMap<>(16,.75f,true);
    private Input working;
    private CompletableFuture<Lines> future;
    private long builds;
    private boolean closed;
    public ContourOverlayCache(){
        this(Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"cartograph-v4-contours");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t;}),true);
    }
    ContourOverlayCache(Executor executor,boolean own){this.executor=executor;owned=own?(ExecutorService)executor:null;}
    private static Lines calculate(Input in){
        var result=ContourLines.build(in.center,in.east,in.south,in.southeast,ContourLines.interval(in.key().lod()));
        if(result.overflow()||result.segments().size()>MAX_SEGMENTS)return new Lines(in,new float[0],new float[0],true);
        float[] data=new float[result.segments().size()*5];int i=0;
        for(var s:result.segments()){
            data[i++]=(float)s.x0();data[i++]=(float)s.z0();data[i++]=(float)s.x1();data[i++]=(float)s.z1();data[i++]=s.height();
        }
        float[] draped=new float[MAX_SEGMENTS*6];int n=0;
        boolean limited=false;
        outer:for(var s:result.segments())for(var line:ContourDrape.split(in,s)){
            if(n+6>draped.length){limited=true;break outer;}
            draped[n++]=(float)line.x0();draped[n++]=(float)line.z0();
            draped[n++]=(float)line.x1();draped[n++]=(float)line.z1();
            draped[n++]=line.surface();draped[n++]=line.height();
        }
        return new Lines(in,data,limited?new float[0]:Arrays.copyOf(draped,n),limited);
    }
    public Snapshot tick(List<Input> inputs){
        if(closed)throw new IllegalStateException("Closed contours");
        int count=Math.min(inputs.size(),MAX_VISIBLE);
        var wanted=new LinkedHashSet<>(inputs.subList(0,count));
        // Same geographic key with a newer center/halo invalidates older dependencies.
        Map<Key,Input> revisions=new HashMap<>();for(var in:wanted)revisions.put(in.key(),in);
        cache.keySet().removeIf(in->revisions.containsKey(in.key())&&!in.equals(revisions.get(in.key())));
        if(future!=null&&future.isDone()){
            try{
                Lines lines=future.join();
                if(wanted.contains(working)){cache.put(working,lines);builds++;}
            }catch(CompletionException|CancellationException ex){
                // Fail closed, remember this dependency to avoid repeated hot-loop failures.
                if(wanted.contains(working))cache.put(working,new Lines(working,new float[0],new float[0],true));
            }
            future=null;working=null;
        }
        long bytes=0;for(var l:cache.values())bytes+=l.bytes();
        while(cache.size()>MAX_RESIDENT||bytes>MAX_BYTES){
            var it=cache.entrySet().iterator();boolean removed=false;
            while(it.hasNext()){
                var entry=it.next();if(wanted.contains(entry.getKey()))continue;
                bytes-=entry.getValue().bytes();it.remove();removed=true;break;
            }
            if(!removed)throw new IllegalStateException("Visible contours exceeded cache budget");
        }
        List<Lines> ready=new ArrayList<>();boolean limited=inputs.size()>count;
        for(Input in:wanted){var line=cache.get(in);if(line!=null){ready.add(line);limited|=line.limited;}}
        if(future==null)for(Input in:wanted)if(!cache.containsKey(in)){
            working=in;future=CompletableFuture.supplyAsync(()->calculate(in),executor);break;
        }
        return new Snapshot(ready,wanted.size(),future==null?0:1,limited,builds);
    }
    @Override public void close(){
        if(closed)return;closed=true;
        if(future!=null)future.cancel(false);
        if(owned!=null)owned.shutdownNow();
        cache.clear();future=null;working=null;
    }
}
