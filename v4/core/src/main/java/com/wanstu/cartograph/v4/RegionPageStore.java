package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** V4-only pages. A store instance is owned by one writer/IO worker for one world/dimension. */
public final class RegionPageStore {
    public static final int EDGE = 32;
    private static final int MAGIC = 0x43563450; // CV4P
    private static final int VERSION = 2;
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
    private final PageTransactionLog log;
    private final int maxCachedPages;
    private final long cacheByteLimit;
    private long estimatedCacheBytes;
    private final LinkedHashMap<Key,Page> cache=new LinkedHashMap<>(16,.75f,true);
    private long diskReads,diskWrites;
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
    public synchronized long diskWrites(){return diskWrites;}
    public synchronized int cachedPages(){return cache.size();}
    public synchronized long estimatedCacheBytes(){return estimatedCacheBytes;}
    public synchronized void clearCache(){cache.clear();estimatedCacheBytes=0;}
    private void remember(Page p){
        Page previous=cache.remove(p.key);
        if(previous!=null)estimatedCacheBytes-=estimatedWeight(previous);
        long weight=estimatedWeight(p);
        if(weight>cacheByteLimit)return;
        while(!cache.isEmpty()&&(cache.size()>=maxCachedPages||estimatedCacheBytes>cacheByteLimit-weight)){
            var it=cache.values().iterator();Page old=it.next();it.remove();
            estimatedCacheBytes-=estimatedWeight(old);
        }
        cache.put(p.key,p);estimatedCacheBytes+=weight;
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
        Page p=cache.get(key);if(p!=null)return p;
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
        Set<Key> seen=new HashSet<>();
        List<byte[]> payloads=new ArrayList<>();
        for(Page p:pages){
            if(!seen.add(p.key)||p.revision<=log.revision(p.key))
                throw new IllegalArgumentException("Duplicate key or stale revision");
            payloads.add(encode(p));
        }
        recoveryRequired=true;
        log.append(pages,payloads);
        diskWrites+=pages.size();
        for(Page p:pages)remember(p);
        recoveryRequired=false;
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
            for(int slot=0;slot<ordered.length;slot++){
                Entry e=ordered[slot];if(e==null)continue;Aggregate a=e.stats;
                out.writeShort(slot);out.writeShort(e.feature==null?0:palette.get(e.feature));
                if(p.key.lod()==0){
                    out.writeInt(a.surfaceCount==0?GeographicPyramid.UNKNOWN_HEIGHT:Math.toIntExact(a.surfaceSum));
                    out.writeInt(a.groundCount==0?GeographicPyramid.UNKNOWN_HEIGHT:Math.toIntExact(a.groundSum));
                    out.writeShort((int)a.depthSum);out.writeShort((int)a.redSum);
                    out.writeShort((int)a.greenSum);out.writeShort((int)a.blueSum);
                    continue;
                }
                out.writeLong(a.known);out.writeLong(a.surfaceCount);out.writeLong(a.groundCount);
                out.writeLong(a.surfaceSum);out.writeLong(a.groundSum);out.writeLong(a.redSum);out.writeLong(a.greenSum);out.writeLong(a.blueSum);
                out.writeLong(a.wetCount);out.writeLong(a.depthSum);out.writeInt(a.minGround);out.writeInt(a.maxGround);
                for(long n:a.histogram)out.writeLong(n);
            }
        }
        byte[] body=raw.toByteArray();if(body.length>MAX_RAW)throw new IOException("Page limit");
        CRC32C crc=new CRC32C();crc.update(body);
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(compressed)){
            out.writeInt(body.length);out.writeInt((int)crc.getValue());
            Deflater codec=new Deflater(Deflater.BEST_SPEED);
            try(DeflaterOutputStream deflate=new DeflaterOutputStream(out,codec,8192)){deflate.write(body);}
            finally{codec.end();}
        }
        return compressed.toByteArray();
    }
    static Page decode(byte[] data,Key expected)throws IOException{
        try(DataInputStream header=new DataInputStream(new ByteArrayInputStream(data))){
            int size=header.readInt(),checksum=header.readInt();
            if(size<1||size>MAX_RAW)throw new IOException("Page length");
            byte[] body;
            try(InflaterInputStream inflater=new InflaterInputStream(header)){
                body=inflater.readNBytes(size+1);
            }
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
                Map<Integer,Entry> entries=new HashMap<>();
                for(int i=0;i<count;i++){
                    int slot=in.readUnsignedShort(),feature=in.readUnsignedShort();
                    if(feature>=palette.size())throw new IOException("Feature index");
                    if(key.lod()==0){
                        if(feature==0)throw new IOException("Missing leaf feature");
                        Entry e=Entry.leaf(palette.get(feature),in.readInt(),in.readInt(),
                            in.readUnsignedShort(),in.readUnsignedShort(),in.readUnsignedShort(),in.readUnsignedShort());
                        if(entries.put(slot,e)!=null)throw new IOException("Duplicate slot");
                        continue;
                    }
                    long known=in.readLong(),sc=in.readLong(),gc=in.readLong(),ss=in.readLong(),gs=in.readLong(),
                        r=in.readLong(),g=in.readLong(),b=in.readLong(),wet=in.readLong(),depth=in.readLong();
                    int min=in.readInt(),max=in.readInt();long[] h=new long[CLASSES];
                    for(int j=0;j<CLASSES;j++)h[j]=in.readLong();
                    Entry e=new Entry(new Aggregate(known,sc,gc,ss,gs,r,g,b,wet,depth,min,max,h),palette.get(feature));
                    if(entries.put(slot,e)!=null)throw new IOException("Duplicate slot");
                }
                if(in.read()!=-1)throw new IOException("Trailing data");
                return new Page(key,revision,entries);
            }
        }catch(IllegalArgumentException e){throw new IOException("Invalid V4 page",e);}
    }
}
