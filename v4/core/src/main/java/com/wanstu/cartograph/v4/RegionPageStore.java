package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32C;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4Exception;
import java.util.concurrent.*;

/** V4-only pages. A store instance is owned by one writer/IO worker for one world/dimension. */
public final class RegionPageStore {
    public static final int EDGE = 32;
    static final int MAGIC = 0x43563450; // CV4P
    static final int VERSION = 6;
    private static final LZ4Factory CODEC=LZ4Factory.safeInstance();
    private static final int MAX_RAW = 4 * 1024 * 1024;
    private static final int MAX_BATCH_PAGES=DiskGeographicPyramid.MAX_BATCH_CHUNKS*11;
    private static final int CLASSES = FeatureDictionary.Terrain.values().length;

    public record Key(int lod, int x, int z) {
        public Key {
            if (lod < 0 || lod > 10) throw new IllegalArgumentException("LOD");
        }
        public static Key of(GeographicPyramid.Cell cell) {
            return new Key(cell.level(), Math.floorDiv(cell.x(), EDGE), Math.floorDiv(cell.z(), EDGE));
        }
        public static int slot(GeographicPyramid.Cell cell) {
            return Math.floorMod(cell.z(), EDGE) * EDGE + Math.floorMod(cell.x(), EDGE);
        }
    }

    /** Exact weighted statistics. All channels are independent of visual style. */
    public record Aggregate(long known, long surfaceCount, long groundCount,
            long surfaceSum, long groundSum, long redSum, long greenSum, long blueSum,
            long wetCount, long depthSum, int minGround, int maxGround, long[] histogram) {
        public Aggregate {
            histogram = histogram.clone();
            if (histogram.length != CLASSES || known <= 0 || known > (1L << 20)
                    || surfaceCount < 0 || surfaceCount > known || groundCount < 0 || groundCount > known
                    || wetCount < 0 || wetCount > known || depthSum < wetCount || depthSum > wetCount*65535
                    || redSum < 0 || redSum > known*65535 || greenSum < 0 || greenSum > known*65535
                    || blueSum < 0 || blueSum > known*65535)
                throw new IllegalArgumentException("Invalid aggregate");
            long total = 0;
            for (long n : histogram) {
                if (n < 0 || n > known) throw new IllegalArgumentException("Invalid histogram");
                total += n;
            }
            if (total != known || (groundCount == 0 ?
                    minGround != GeographicPyramid.UNKNOWN_HEIGHT || maxGround != GeographicPyramid.UNKNOWN_HEIGHT || groundSum != 0
                    : minGround == GeographicPyramid.UNKNOWN_HEIGHT || maxGround < minGround
                        || groundSum < groundCount*(long)minGround || groundSum > groundCount*(long)maxGround)
                    || (surfaceCount == 0 && surfaceSum != 0))
                throw new IllegalArgumentException("Invalid height/histogram");
        }
        @Override public long[] histogram() { return histogram.clone(); }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Aggregate a)) return false;
            return known==a.known && surfaceCount==a.surfaceCount && groundCount==a.groundCount &&
                surfaceSum==a.surfaceSum && groundSum==a.groundSum && redSum==a.redSum &&
                greenSum==a.greenSum && blueSum==a.blueSum && wetCount==a.wetCount &&
                depthSum==a.depthSum && minGround==a.minGround && maxGround==a.maxGround &&
                Arrays.equals(histogram,a.histogram);
        }
        @Override public int hashCode() {
            return 31*Objects.hash(known,surfaceCount,groundCount,surfaceSum,groundSum,redSum,
                greenSum,blueSum,wetCount,depthSum,minGround,maxGround)+Arrays.hashCode(histogram);
        }
        public static Aggregate from(GeographicPyramid.Stats s) {
            return new Aggregate(s.known(),s.surfaceCount(),s.groundCount(),s.surfaceSum(),
                s.groundSum(),s.redSum(),s.greenSum(),s.blueSum(),s.wetCount(),s.depthSum(),
                s.minGround(),s.maxGround(),s.histogram());
        }
        public static Aggregate merge(Collection<Aggregate> children) {
            long n=0,sc=0,gc=0,ss=0,gs=0,r=0,g=0,b=0,w=0,d=0;
            int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
            long[] h=new long[CLASSES];
            for(Aggregate a:children) {
                if(a==null)continue;
                n+=a.known;sc+=a.surfaceCount;gc+=a.groundCount;ss+=a.surfaceSum;gs+=a.groundSum;
                r+=a.redSum;g+=a.greenSum;b+=a.blueSum;w+=a.wetCount;d+=a.depthSum;
                if(a.groundCount>0){min=Math.min(min,a.minGround);max=Math.max(max,a.maxGround);}
                for(int i=0;i<CLASSES;i++)h[i]+=a.histogram[i];
            }
            if(n==0)return null;
            return new Aggregate(n,sc,gc,ss,gs,r,g,b,w,d,
                gc==0?GeographicPyramid.UNKNOWN_HEIGHT:min,gc==0?GeographicPyramid.UNKNOWN_HEIGHT:max,h);
        }
    }

    /** Exact L0 feature identity. No dependency on runtime registry integer IDs. */
    public record Entry(Aggregate stats, FeatureDictionary.Feature feature) {
        public Entry { Objects.requireNonNull(stats); }
        public static Entry leaf(FeatureDictionary.Feature feature,int surface,int ground,int depth,int r,int g,int b) {
            Objects.requireNonNull(feature);
            if(depth<0||depth>65535||r<0||r>65535||g<0||g>65535||b<0||b>65535)
                throw new IllegalArgumentException("Invalid leaf");
            boolean sc=surface!=GeographicPyramid.UNKNOWN_HEIGHT,gc=ground!=GeographicPyramid.UNKNOWN_HEIGHT;
            long[] h=new long[CLASSES];h[feature.terrain().ordinal()]=1;
            return new Entry(new Aggregate(1,sc?1:0,gc?1:0,sc?surface:0,gc?ground:0,
                r,g,b,depth>0?1:0,depth,ground,ground,h),feature);
        }
    }

    /** Immutable indexed page. Materialize the public map view only for consumers that ask for it. */
    public static final class Page {
        private final Key key;
        private final long revision;
        private final Entry[] slots;
        private final int size;
        private volatile Map<Integer,Entry> view;
        public Page(Key key,long revision,Map<Integer,Entry> entries){
            this(key,revision,toSlots(entries));
        }
        private static Entry[] toSlots(Map<Integer,Entry> entries){
            Entry[] slots=new Entry[EDGE*EDGE];
            for(var pair:entries.entrySet()){
                int slot=pair.getKey();
                if(slot<0||slot>=slots.length)throw new IllegalArgumentException("Page slot");
                slots[slot]=Objects.requireNonNull(pair.getValue());
            }
            return slots;
        }
        private Page(Key key,long revision,Entry[] slots){
            this.key=Objects.requireNonNull(key);
            this.revision=revision;
            if(revision<0||slots.length!=EDGE*EDGE)throw new IllegalArgumentException("Page");
            this.slots=slots;
            long capacity=1L<<(key.lod()*2);
            int count=0;
            for(Entry v:slots){
                if(v==null)continue;
                if(v.stats.known>capacity||(key.lod()==0)!=(v.feature!=null)||
                    (v.feature!=null&&!validLeaf(v)))throw new IllegalArgumentException("Page slot");
                count++;
            }
            size=count;
        }
        static Page fromSlots(Key key,long revision,Entry[] slots){
            return new Page(key,revision,slots.clone());
        }
        public Key key(){return key;}
        public long revision(){return revision;}
        public Entry entryAt(int slot){return slots[slot];}
        Entry[] copySlots(){return slots.clone();}
        public int size(){return size;}
        public Map<Integer,Entry> entries(){
            Map<Integer,Entry> result=view;
            if(result!=null)return result;
            Map<Integer,Entry> entries=new HashMap<>(size*4/3+1);
            for(int i=0;i<slots.length;i++)if(slots[i]!=null)entries.put(i,slots[i]);
            result=Map.copyOf(entries);view=result;return result;
        }
        @Override public boolean equals(Object o){
            return o instanceof Page p&&key.equals(p.key)&&revision==p.revision&&Arrays.equals(slots,p.slots);
        }
        @Override public int hashCode(){return 31*Objects.hash(key,revision)+Arrays.hashCode(slots);}
    }
    static boolean validLeaf(Entry v){
        Aggregate a=v.stats;
        if(a.known!=1||a.histogram[v.feature.terrain().ordinal()]!=1)return false;
        if(a.surfaceCount==1&&(a.surfaceSum<=Integer.MIN_VALUE||a.surfaceSum>Integer.MAX_VALUE))return false;
        if(a.groundCount==1&&(a.minGround!=a.maxGround||a.groundSum!=a.minGround))return false;
        return true;
    }
    // Process-wide bounded CPU concurrency; default leaves cores for Minecraft.
    // Benchmark can override it explicitly. No common-pool usage or unbounded task queue.
    private static final int ENCODE_THREADS=Math.max(1,Math.min(4,
        Integer.getInteger("cartograph.v4.encodeThreads",2)));
    private static final ExecutorService ENCODERS=Executors.newFixedThreadPool(ENCODE_THREADS,r->{
        Thread thread=new Thread(r,"cartograph-v4-page-encode");
        thread.setDaemon(true);thread.setPriority(Thread.NORM_PRIORITY-1);return thread;
    });
    private static final Semaphore ENCODE_PERMITS=new Semaphore(ENCODE_THREADS);
    private final PageTransactionLog log;
    private final int maxCachedPages;
    private final long cacheByteLimit;
    private long estimatedCacheBytes;
    private record Cached(Page page,long weight) {}
    private final LinkedHashMap<Key,Cached> cache=new LinkedHashMap<>(16,.75f,true);
    private long diskReads,diskWrites,encodeNanos,appendNanos,cacheNanos;
    private boolean recoveryRequired;

    /** root must be a new V4 world/dimension directory, never a legacy directory. */
    public RegionPageStore(Path root,int maxCachedPages) throws IOException {
        this(root,maxCachedPages,Long.MAX_VALUE);
    }
    public RegionPageStore(Path root,int maxCachedPages,long cacheByteLimit) throws IOException {
        if(maxCachedPages<1||cacheByteLimit<1)throw new IllegalArgumentException("cache");
        this.cacheByteLimit=cacheByteLimit;
        this.maxCachedPages=maxCachedPages;
        Files.createDirectories(root);
        log=new PageTransactionLog(root);
    }
    public synchronized long durableFlushes(){return log.flushes();}
    public synchronized long startupScanBytes(){return log.scanBytes();}
    public synchronized long diskReads(){return diskReads;}
    public synchronized long encodeNanos(){return encodeNanos;}
    public synchronized long appendNanos(){return appendNanos;}
    public synchronized long cacheNanos(){return cacheNanos;}
    public synchronized long diskWrites(){return diskWrites;}
    public synchronized int cachedPages(){return cache.size();}
    public synchronized long estimatedCacheBytes(){return estimatedCacheBytes;}
    public synchronized void clearCache(){cache.clear();estimatedCacheBytes=0;}
    private void remember(Page p){
        Cached previous=cache.remove(p.key);
        if(previous!=null)estimatedCacheBytes-=previous.weight;
        long weight=estimatedWeight(p);
        if(weight>cacheByteLimit)return;
        while(!cache.isEmpty()&&(cache.size()>=maxCachedPages||estimatedCacheBytes>cacheByteLimit-weight)){
            var it=cache.values().iterator();Cached old=it.next();it.remove();
            estimatedCacheBytes-=old.weight;
        }
        cache.put(p.key,new Cached(p,weight));estimatedCacheBytes+=weight;
    }

    // Conservative accounting model, not measured JVM retained size.
    private static long estimatedWeight(Page p){
        long weight=4352;
        for(Entry e:p.slots){
            if(e==null)continue;
            weight+=384;
            if(e.feature!=null)weight+=128+2L*(e.feature.materialKey().length()+e.feature.biomeKey().length());
        }
        return weight;
    }
    public synchronized Page read(Key key)throws IOException{
        if(recoveryRequired)throw new IOException("Recover pending transaction first");
        Cached hit=cache.get(key);if(hit!=null)return hit.page;
        Page p;
        byte[] data=log.read(key);
        if(data==null){p=new Page(key,0,Map.of());remember(p);return p;}
        diskReads++;
        p=decode(data,key);remember(p);return p;
    }

    /** Commit the entire batch with one durable append; no per-page fsync or rename. */
    public synchronized void writeBatch(Collection<Page> input)throws IOException{
        if(recoveryRequired)throw new IOException("Reopen store after failed transaction");
        List<Page> pages=List.copyOf(input);
        if(pages.isEmpty())return;
        if(pages.size()>MAX_BATCH_PAGES)throw new IllegalArgumentException("Batch limit");
        long start=System.nanoTime();
        Set<Key> seen=new HashSet<>();
        for(Page p:pages){
            if(!seen.add(p.key)||p.revision<=log.revision(p.key))
                throw new IllegalArgumentException("Duplicate key or stale revision");
        }
        List<byte[]> payloads=encodeBatch(pages);
        encodeNanos+=System.nanoTime()-start;
        recoveryRequired=true;
        start=System.nanoTime();
        log.append(pages,payloads);
        appendNanos+=System.nanoTime()-start;
        start=System.nanoTime();
        diskWrites+=pages.size();
        for(Page p:pages)remember(p);
        cacheNanos+=System.nanoTime()-start;
        recoveryRequired=false;
    }

    private static List<byte[]> encodeBatch(List<Page> pages)throws IOException{
        if(ENCODE_THREADS==1||pages.size()<4){
            List<byte[]> result=new ArrayList<>(pages.size());
            for(Page p:pages)result.add(encode(p));
            return result;
        }
        byte[][] result=new byte[pages.size()][];
        List<Future<?>> tasks=new ArrayList<>();
        int workers=Math.min(ENCODE_THREADS,pages.size());
        try{
            for(int worker=0;worker<workers;worker++){
                final int start=worker;
                ENCODE_PERMITS.acquire();
                try{
                    tasks.add(ENCODERS.submit(()->{
                        try{
                            for(int i=start;i<pages.size();i+=workers)result[i]=encode(pages.get(i));
                            return null;
                        }finally{ENCODE_PERMITS.release();}
                    }));
                }catch(RuntimeException e){ENCODE_PERMITS.release();throw e;}
            }
            for(Future<?> task:tasks)task.get();
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();throw new IOException("Encoding interrupted",e);
        }catch(ExecutionException e){throw new IOException("Page encoding failed",e.getCause());}
        return Arrays.asList(result);
    }
    // Counts use the smallest exact unsigned width for the LOD capacity.
    static int countWidth(int lod){return lod<=3?1:lod<=7?2:4;}
    static int colorWidth(int lod){return lod<=8?4:8;}
    static int stride(int lod){
        return lod==0?20:2+(4+CLASSES)*countWidth(lod)+16+4*colorWidth(lod)+8;
    }
    private static long getUnsigned(ByteBuffer in,int width){
        return switch(width){
            case 1 -> Byte.toUnsignedLong(in.get());
            case 2 -> Short.toUnsignedLong(in.getShort());
            case 4 -> Integer.toUnsignedLong(in.getInt());
            case 8 -> in.getLong();
            default -> throw new IllegalArgumentException("width");
        };
    }
    /** Thread-owned scratch bounded by one full page; never escapes to stored pages or results. */
    private static final ThreadLocal<byte[]> ROW_SCRATCH=ThreadLocal.withInitial(()->new byte[EDGE*EDGE*stride(10)]);
    private static void plane2(byte[] b,int p,int n,int value){
        b[p]=(byte)(value>>>8);b[p+n]=(byte)value;
    }
    private static void plane4(byte[] b,int p,int n,int value){
        b[p]=(byte)(value>>>24);b[p+n]=(byte)(value>>>16);
        b[p+2*n]=(byte)(value>>>8);b[p+3*n]=(byte)value;
    }
    private static void plane(byte[] b,int p,int n,long value,int width){
        switch(width){
            case 1 -> b[p]=(byte)value;
            case 2 -> plane2(b,p,n,(int)value);
            case 4 -> plane4(b,p,n,(int)value);
            case 8 -> {plane4(b,p,n,(int)(value>>>32));plane4(b,p+4*n,n,(int)value);}
            default -> throw new IllegalArgumentException("width");
        }
    }
    static byte[] encode(Page p)throws IOException{
        ByteArrayOutputStream header=new ByteArrayOutputStream(256);
        Map<FeatureDictionary.Feature,Integer> palette=new LinkedHashMap<>();
        Entry[] ordered=p.slots;
        int lod=p.key.lod(),stride=stride(lod),cw=countWidth(lod),sw=colorWidth(lod);
        if(lod==0){
            for(Entry e:ordered)if(e!=null)palette.computeIfAbsent(e.feature,f->palette.size()+1);
        }
        try(DataOutputStream out=new DataOutputStream(header)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(lod);out.writeInt(p.key.x());out.writeInt(p.key.z());
            out.writeLong(p.revision);
            out.writeInt(palette.size());
            for(var f:palette.keySet()){out.writeUTF(f.materialKey());out.writeUTF(f.biomeKey());out.writeByte(f.terrain().ordinal());}
            out.writeInt(p.size);
        }
        int n=p.size;boolean dense=n==EDGE*EDGE;
        if(dense)stride-=2;
        int bytes=n*stride;
        byte[] prefix=header.toByteArray();
        if((long)prefix.length+bytes>MAX_RAW)throw new IOException("Page limit");
        byte[] body=Arrays.copyOf(prefix,prefix.length+bytes);
        int row=0,base=prefix.length;
        FeatureDictionary.Feature lastFeature=null;
        int lastId=0;
        if(lod==0){
            for(int slot=0;slot<ordered.length;slot++){
                Entry e=ordered[slot];if(e==null)continue;Aggregate a=e.stats;
                if(e.feature!=lastFeature){lastFeature=e.feature;lastId=palette.get(e.feature);}
                int pos=base+row++;
                if(!dense){plane2(body,pos,n,slot);pos+=2*n;}
                plane2(body,pos,n,lastId);pos+=2*n;
                plane4(body,pos,n,a.surfaceCount==0?GeographicPyramid.UNKNOWN_HEIGHT:(int)a.surfaceSum);pos+=4*n;
                plane4(body,pos,n,a.groundCount==0?GeographicPyramid.UNKNOWN_HEIGHT:(int)a.groundSum);pos+=4*n;
                plane2(body,pos,n,(int)a.depthSum);pos+=2*n;
                plane2(body,pos,n,(int)a.redSum);pos+=2*n;
                plane2(body,pos,n,(int)a.greenSum);pos+=2*n;
                plane2(body,pos,n,(int)a.blueSum);
            }
        }else{
            for(int slot=0;slot<ordered.length;slot++){
                Entry e=ordered[slot];if(e==null)continue;Aggregate a=e.stats;
                int pos=base+row++;
                if(!dense){plane2(body,pos,n,slot);pos+=2*n;}
                plane(body,pos,n,a.known,cw);pos+=cw*n;
                plane(body,pos,n,a.surfaceCount,cw);pos+=cw*n;
                plane(body,pos,n,a.groundCount,cw);pos+=cw*n;
                plane(body,pos,n,a.wetCount,cw);pos+=cw*n;
                plane(body,pos,n,a.surfaceSum,8);pos+=8*n;
                plane(body,pos,n,a.groundSum,8);pos+=8*n;
                plane(body,pos,n,a.redSum,sw);pos+=sw*n;
                plane(body,pos,n,a.greenSum,sw);pos+=sw*n;
                plane(body,pos,n,a.blueSum,sw);pos+=sw*n;
                plane(body,pos,n,a.depthSum,sw);pos+=sw*n;
                plane4(body,pos,n,a.minGround);pos+=4*n;
                plane4(body,pos,n,a.maxGround);pos+=4*n;
                for(long value:a.histogram){plane(body,pos,n,value,cw);pos+=cw*n;}
            }
        }
        if(row!=n)throw new IOException("Incorrect numeric record count");
        CRC32C crc=new CRC32C();crc.update(body);
        var compressor=CODEC.fastCompressor();
        byte[] compressed=new byte[8+compressor.maxCompressedLength(body.length)];
        ByteBuffer.wrap(compressed).putInt(body.length).putInt((int)crc.getValue());
        int length=compressor.compress(body,0,body.length,compressed,8,compressed.length-8);
        return Arrays.copyOf(compressed,8+length);
    }
    static Page decode(byte[] data,Key expected)throws IOException{
        try(DataInputStream header=new DataInputStream(new ByteArrayInputStream(data))){
            int size=header.readInt(),checksum=header.readInt();
            if(size<1||size>MAX_RAW)throw new IOException("Page length");
            byte[] body=new byte[size];
            try{
                int n=CODEC.safeDecompressor().decompress(data,8,data.length-8,body,0,size);
                if(n!=size)throw new IOException("Page decompressed size");
            }catch(LZ4Exception e){throw new IOException("Invalid page compression",e);}
            CRC32C crc=new CRC32C();crc.update(body);
            if(body.length!=size||(int)crc.getValue()!=checksum)throw new IOException("Page checksum");
            try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(body))){
                if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Not V4 page");
                Key key=new Key(in.readInt(),in.readInt(),in.readInt());
                if(!key.equals(expected))throw new IOException("Page identity");
                long revision=in.readLong();
                int n=in.readInt();if(n<0||n>EDGE*EDGE)throw new IOException("Palette count");
                List<FeatureDictionary.Feature> palette=new ArrayList<>();palette.add(null);
                for(int i=0;i<n;i++){
                    String material=in.readUTF(),biome=in.readUTF();int terrain=in.readUnsignedByte();
                    if(terrain>=CLASSES)throw new IOException("Terrain class");
                    palette.add(new FeatureDictionary.Feature(material,biome,FeatureDictionary.Terrain.values()[terrain]));
                }
                int count=in.readInt();if(count<0||count>EDGE*EDGE)throw new IOException("Entries");
                int lod=key.lod(),stride=stride(lod),cw=countWidth(lod),sw=colorWidth(lod);
                boolean dense=count==EDGE*EDGE;if(dense)stride-=2;
                if(in.available()!=count*stride)throw new IOException("Numeric page length");
                int base=body.length-in.available();
                byte[] rows=ROW_SCRATCH.get();
                for(int col=0;col<stride;col++)for(int row=0,dest=col,src=base+col*count;row<count;row++,dest+=stride,src++)
                    rows[dest]=body[src];
                ByteBuffer cells=ByteBuffer.wrap(rows,0,count*stride);
                Entry[] entries=new Entry[EDGE*EDGE];
                for(int i=0;i<count;i++){
                    int slot=dense?i:Short.toUnsignedInt(cells.getShort()),feature=lod==0?Short.toUnsignedInt(cells.getShort()):0;
                    if(feature>=palette.size())throw new IOException("Feature index");
                    if(key.lod()==0){
                        if(feature==0)throw new IOException("Missing leaf feature");
                        Entry e=Entry.leaf(palette.get(feature),cells.getInt(),cells.getInt(),
                            Short.toUnsignedInt(cells.getShort()),Short.toUnsignedInt(cells.getShort()),
                            Short.toUnsignedInt(cells.getShort()),Short.toUnsignedInt(cells.getShort()));
                        if(slot>=entries.length||entries[slot]!=null)throw new IOException("Invalid or duplicate slot");
                        entries[slot]=e;
                        continue;
                    }
                    long known=getUnsigned(cells,cw),sc=getUnsigned(cells,cw),gc=getUnsigned(cells,cw),wet=getUnsigned(cells,cw);
                    long ss=cells.getLong(),gs=cells.getLong();
                    long r=getUnsigned(cells,sw),g=getUnsigned(cells,sw),b=getUnsigned(cells,sw),depth=getUnsigned(cells,sw);
                    int min=cells.getInt(),max=cells.getInt();long[] h=new long[CLASSES];
                    for(int j=0;j<CLASSES;j++)h[j]=getUnsigned(cells,cw);
                    Entry e=new Entry(new Aggregate(known,sc,gc,ss,gs,r,g,b,wet,depth,min,max,h),palette.get(feature));
                    if(slot>=entries.length||entries[slot]!=null)throw new IOException("Invalid or duplicate slot");
                        entries[slot]=e;
                }
                return new Page(key,revision,entries);
            }
        }catch(IllegalArgumentException e){throw new IOException("Invalid V4 page",e);}
    }
}
