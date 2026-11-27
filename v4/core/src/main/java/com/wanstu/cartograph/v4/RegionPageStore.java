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
    private static final int MAGIC = 0x43563450; // CV4P
    private static final int VERSION = 5;
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

    public record Page(Key key, long revision, Map<Integer,Entry> entries) {
        public Page {
            Objects.requireNonNull(key);
            entries = Map.copyOf(entries);
            if(revision<0 || entries.size()>EDGE*EDGE)throw new IllegalArgumentException("Page");
            long capacity=1L<<(key.lod()*2);
            for(var e:entries.entrySet()) {
                Entry v=e.getValue();
                if(e.getKey()<0||e.getKey()>=EDGE*EDGE||v.stats.known>capacity||
                    (key.lod()==0)!=(v.feature!=null)||
                    (v.feature!=null && !validLeaf(v)))
                    throw new IllegalArgumentException("Page slot");
            }
        }
    }

    private static boolean validLeaf(Entry v){
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
        long weight=256;
        for(Entry e:p.entries.values()){
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
    private static int countWidth(int lod){return lod<=3?1:lod<=7?2:4;}
    private static int colorWidth(int lod){return lod<=8?4:8;}
    private static int stride(int lod){
        return lod==0?20:2+(4+CLASSES)*countWidth(lod)+16+4*colorWidth(lod)+8;
    }
    private static void putUnsigned(ByteBuffer out,long n,int width){
        switch(width){
            case 1 -> out.put((byte)n);
            case 2 -> out.putShort((short)n);
            case 4 -> out.putInt((int)n);
            case 8 -> out.putLong(n);
            default -> throw new IllegalArgumentException("width");
        }
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
    static byte[] encode(Page p)throws IOException{
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(raw)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(p.key.lod());out.writeInt(p.key.x());out.writeInt(p.key.z());
            out.writeLong(p.revision);
            // Region-local palette, independent of process-local IDs.
            Map<FeatureDictionary.Feature,Integer> palette=new LinkedHashMap<>();
            Entry[] ordered=new Entry[EDGE*EDGE];
            p.entries.forEach((slot,e)->ordered[slot]=e);
            for(Entry e:ordered)if(e!=null&&e.feature!=null)palette.computeIfAbsent(e.feature,f->palette.size()+1);
            out.writeInt(palette.size());
            for(var f:palette.keySet()){out.writeUTF(f.materialKey());out.writeUTF(f.biomeKey());out.writeByte(f.terrain().ordinal());}
            out.writeInt(p.entries.size());
            // Fixed-width numeric records: bulk byte buffer instead of dozens of stream calls per cell.
            int lod=p.key.lod(),stride=stride(lod),cw=countWidth(lod),sw=colorWidth(lod);
            ByteBuffer cells=ByteBuffer.allocate(Math.multiplyExact(p.entries.size(),stride));
            for(int slot=0;slot<ordered.length;slot++){
                Entry e=ordered[slot];if(e==null)continue;Aggregate a=e.stats;
                cells.putShort((short)slot);
                if(lod==0){
                    cells.putShort((short)(int)palette.get(e.feature));
                    cells.putInt(a.surfaceCount==0?GeographicPyramid.UNKNOWN_HEIGHT:Math.toIntExact(a.surfaceSum));
                    cells.putInt(a.groundCount==0?GeographicPyramid.UNKNOWN_HEIGHT:Math.toIntExact(a.groundSum));
                    cells.putShort((short)a.depthSum).putShort((short)a.redSum);
                    cells.putShort((short)a.greenSum).putShort((short)a.blueSum);
                    continue;
                }
                putUnsigned(cells,a.known,cw);putUnsigned(cells,a.surfaceCount,cw);
                putUnsigned(cells,a.groundCount,cw);putUnsigned(cells,a.wetCount,cw);
                cells.putLong(a.surfaceSum).putLong(a.groundSum);
                putUnsigned(cells,a.redSum,sw);putUnsigned(cells,a.greenSum,sw);
                putUnsigned(cells,a.blueSum,sw);putUnsigned(cells,a.depthSum,sw);
                cells.putInt(a.minGround).putInt(a.maxGround);
                for(long n:a.histogram)putUnsigned(cells,n,cw);
            }
            byte[] rowMajor=cells.array(),planes=new byte[rowMajor.length];
            int n=p.entries.size();
            for(int row=0;row<n;row++)for(int col=0;col<stride;col++)
                planes[col*n+row]=rowMajor[row*stride+col];
            out.write(planes);
        }
        byte[] body=raw.toByteArray();if(body.length>MAX_RAW)throw new IOException("Page limit");
        CRC32C crc=new CRC32C();crc.update(body);
        var compressor=CODEC.fastCompressor();
        byte[] compressed=new byte[8+compressor.maxCompressedLength(body.length)];
        ByteBuffer.wrap(compressed).putInt(body.length).putInt((int)crc.getValue());
        int n=compressor.compress(body,0,body.length,compressed,8,compressed.length-8);
        return Arrays.copyOf(compressed,8+n);
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
                if(in.available()!=count*stride)throw new IOException("Numeric page length");
                int base=body.length-in.available();
                byte[] rows=new byte[count*stride];
                for(int row=0;row<count;row++)for(int col=0;col<stride;col++)
                    rows[row*stride+col]=body[base+col*count+row];
                ByteBuffer cells=ByteBuffer.wrap(rows);
                Map<Integer,Entry> entries=new HashMap<>(count*4/3+1);
                for(int i=0;i<count;i++){
                    int slot=Short.toUnsignedInt(cells.getShort()),feature=lod==0?Short.toUnsignedInt(cells.getShort()):0;
                    if(feature>=palette.size())throw new IOException("Feature index");
                    if(key.lod()==0){
                        if(feature==0)throw new IOException("Missing leaf feature");
                        Entry e=Entry.leaf(palette.get(feature),cells.getInt(),cells.getInt(),
                            Short.toUnsignedInt(cells.getShort()),Short.toUnsignedInt(cells.getShort()),
                            Short.toUnsignedInt(cells.getShort()),Short.toUnsignedInt(cells.getShort()));
                        if(entries.put(slot,e)!=null)throw new IOException("Duplicate slot");
                        continue;
                    }
                    long known=getUnsigned(cells,cw),sc=getUnsigned(cells,cw),gc=getUnsigned(cells,cw),wet=getUnsigned(cells,cw);
                    long ss=cells.getLong(),gs=cells.getLong();
                    long r=getUnsigned(cells,sw),g=getUnsigned(cells,sw),b=getUnsigned(cells,sw),depth=getUnsigned(cells,sw);
                    int min=cells.getInt(),max=cells.getInt();long[] h=new long[CLASSES];
                    for(int j=0;j<CLASSES;j++)h[j]=getUnsigned(cells,cw);
                    Entry e=new Entry(new Aggregate(known,sc,gc,ss,gs,r,g,b,wet,depth,min,max,h),palette.get(feature));
                    if(entries.put(slot,e)!=null)throw new IOException("Duplicate slot");
                }
                return new Page(key,revision,entries);
            }
        }catch(IllegalArgumentException e){throw new IOException("Invalid V4 page",e);}
    }
}
