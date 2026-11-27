package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** Fresh-shard-only bottom-up builder. Each physical LOD page is generated once. */
public final class FreshRegionBuilder implements AutoCloseable {
    private static final int N=32*32,H=FeatureDictionary.Terrain.values().length;
    private static final int KNOWN=0,SC=1,GC=2,WET=3,SS=4,GS=5,R=6,G=7,B=8,DEPTH=9,MIN=10,MAX=11,HIST=12;
    private static final int UNKNOWN=GeographicPyramid.UNKNOWN_HEIGHT;

    /** A complete observed chunk, or null if it has not been generated. */
    @FunctionalInterface public interface Source { Entry[] chunk(int x,int z)throws IOException; }
    @FunctionalInterface public interface PackedSource { Columns chunk(int x,int z)throws IOException; }
    /** Immutable dense chunk snapshot. Null feature marks an unobserved column. */
    public static final class Columns {
        private final FeatureDictionary.Feature[] features;
        private final int[] surface,ground;
        private final char[] depth,red,green,blue;
        private final int count;
        public Columns(FeatureDictionary.Feature[] features,int[] surface,int[] ground,
                       char[] depth,char[] red,char[] green,char[] blue){
            if(features.length!=256||surface.length!=256||ground.length!=256||
                depth.length!=256||red.length!=256||green.length!=256||blue.length!=256)
                throw new IllegalArgumentException("Chunk column length");
            this.features=features.clone();this.surface=surface.clone();this.ground=ground.clone();
            this.depth=depth.clone();this.red=red.clone();this.green=green.clone();this.blue=blue.clone();
            int n=0;for(var feature:this.features)if(feature!=null)n++;count=n;
        }
        public static Columns fromEntries(Entry[] entries)throws IOException{
            if(entries==null)return null;
            if(entries.length!=256)throw new IOException("Invalid chunk size");
            var f=new FeatureDictionary.Feature[256];
            int[] s=new int[256],g=new int[256];char[] d=new char[256],r=new char[256],gr=new char[256],b=new char[256];
            for(int i=0;i<256;i++){
                Entry e=entries[i];if(e==null)continue;
                if(e.feature()==null||!RegionPageStore.validLeaf(e))throw new IOException("Invalid leaf");
                Aggregate a=e.stats();f[i]=e.feature();
                s[i]=a.surfaceCount()==0?UNKNOWN:(int)a.surfaceSum();
                g[i]=a.groundCount()==0?UNKNOWN:(int)a.groundSum();
                d[i]=(char)a.depthSum();r[i]=(char)a.redSum();gr[i]=(char)a.greenSum();b[i]=(char)a.blueSum();
            }
            return new Columns(f,s,g,d,r,gr,b);
        }
    }
    public record Result(int chunks,long pages,long flushes,long sourceNanos,long mergeNanos,long encodeNanos,long commitNanos){}
    private final PackedSource source;
    private final PageTransactionLog log;
    private final List<PageTransactionLog.Encoded> pending=new ArrayList<>();
    private int pendingBytes,chunks;
    private final Packed[] scratch=new Packed[11];
    private long pages,sourceNanos,mergeNanos,encodeNanos,commitNanos;
    private FreshRegionBuilder(Path stage,PackedSource source)throws IOException{
        this.source=source;log=new PageTransactionLog(stage);
    }
    public static Result build(Path root,RegionShard.Id id,Source source)throws IOException{
        return buildPacked(root,id,(x,z)->Columns.fromEntries(source.chunk(x,z)));
    }
    public static Result buildPacked(Path root,RegionShard.Id id,PackedSource source)throws IOException{
        Objects.requireNonNull(source);
        Files.createDirectories(root);
        Path target=id.directory(root);
        if(Files.exists(target))throw new IOException("Fresh builder cannot overwrite an existing shard");
        Path stage=Files.createTempDirectory(root,".building-");
        try(var b=new FreshRegionBuilder(stage,source)){
            Packed p=b.buildPage(5,id.x(),id.z());
            for(int lod=6;lod<=10;lod++){
                Key parent=new Key(lod,Math.floorDiv(p.key.x(),2),Math.floorDiv(p.key.z(),2));
                Packed q=b.page(parent);b.merge(q,p);b.emit(q);p=q;
            }
            b.flush();
            long syncStart=System.nanoTime();b.log.forceStaged();b.log.checkpoint();b.commitNanos+=System.nanoTime()-syncStart;
            if(Files.exists(target))throw new IOException("Shard already published");
            Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);
            return new Result(b.chunks,b.pages,b.log.flushes(),b.sourceNanos,b.mergeNanos,b.encodeNanos,b.commitNanos);
        }finally{
            if(Files.exists(stage))try(var files=Files.walk(stage)){
                for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);
            }
        }
    }
    @Override public void close()throws IOException{log.closeStaged();}
    private Packed page(Key key){
        Packed p=scratch[key.lod()];
        if(p==null){p=new Packed(key);scratch[key.lod()]=p;}
        else p.reset(key);
        return p;
    }
    private Packed buildPage(int lod,int x,int z)throws IOException{
        Packed p=page(new Key(lod,x,z));
        if(lod==0){
            int cx=Math.multiplyExact(x,2),cz=Math.multiplyExact(z,2);
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                long start=System.nanoTime();Columns chunk=source.chunk(cx+dx,cz+dz);sourceNanos+=System.nanoTime()-start;
                if(chunk==null)continue;
                chunks++;p.leaves[dz*2+dx]=chunk;p.count+=chunk.count;
            }
        }else{
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)
                merge(p,buildPage(lod-1,x*2+dx,z*2+dz));
        }
        emit(p);
        return p;
    }
    private void merge(Packed parent,Packed child){long start=System.nanoTime();parent.mergeChild(child);mergeNanos+=System.nanoTime()-start;}
    private void emit(Packed p)throws IOException{
        if(p.count==0)return;
        long start=System.nanoTime();byte[] bytes=p.encode();encodeNanos+=System.nanoTime()-start;
        if(!pending.isEmpty()&&(pending.size()>=256||pendingBytes+bytes.length+24>8*1024*1024))flush();
        pending.add(new PageTransactionLog.Encoded(p.key,1,bytes));pendingBytes+=bytes.length+24;pages++;
    }
    private void flush()throws IOException{
        if(pending.isEmpty())return;
        long start=System.nanoTime();log.appendStaged(pending);commitNanos+=System.nanoTime()-start;pending.clear();pendingBytes=0;
    }
    private static final class Packed {
        Key key;
        final long[][] data;
        final Columns[] leaves;
        int count;
        Packed(Key key){
            this.key=key;
            boolean leaf=key.lod()==0;
            data=leaf?null:new long[HIST+H][N];
            leaves=leaf?new Columns[4]:null;
        }
        void reset(Key key){
            this.key=key;count=0;
            if(leaves!=null)Arrays.fill(leaves,null);
            else for(long[] channel:data)Arrays.fill(channel,0);
        }
        private void mergeLeaf(Packed child,int ox,int oz){
            long[][] d=data;
            for(int quadrant=0;quadrant<4;quadrant++){
                Columns c=child.leaves[quadrant];if(c==null||c.count==0)continue;
                int qx=(quadrant%2)*8,qz=(quadrant/2)*8;
                for(int z=0;z<8;z++)for(int x=0;x<8;x++){
                    int from=z*32+x*2,to=(z+oz+qz)*32+x+ox+qx;
                    int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
                    for(int k=0;k<4;k++){
                        int i=from+(k/2)*16+k%2;
                        var f=c.features[i];if(f==null)continue;
                        int s=c.surface[i],g=c.ground[i],depth=c.depth[i];
                        d[KNOWN][to]++;
                        if(s!=UNKNOWN){d[SC][to]++;d[SS][to]+=s;}
                        if(g!=UNKNOWN){
                            d[GC][to]++;d[GS][to]+=g;min=Math.min(min,g);max=Math.max(max,g);
                        }
                        if(depth>0)d[WET][to]++;
                        d[DEPTH][to]+=depth;d[R][to]+=c.red[i];d[G][to]+=c.green[i];d[B][to]+=c.blue[i];
                        d[HIST+f.terrain().ordinal()][to]++;
                    }
                    if(d[KNOWN][to]==0)continue;
                    count++;d[MIN][to]=d[GC][to]==0?UNKNOWN:min;d[MAX][to]=d[GC][to]==0?UNKNOWN:max;
                }
            }
        }
        private Columns chunkAt(int slot){return leaves[(slot/512)*2+(slot%32)/16];}
        private static int columnAt(int slot){return ((slot/32)%16)*16+slot%16;}
        void mergeChild(Packed child){
            if(child.count==0)return;
            int ox=Math.floorMod(child.key.x(),2)*16,oz=Math.floorMod(child.key.z(),2)*16;
            if(child.leaves!=null){mergeLeaf(child,ox,oz);return;}
            long[][] c=child.data,d=data;
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                int i=z*64+x*2,j=i+1,k=i+32,l=k+1,to=(z+oz)*32+x+ox;
                long n=c[KNOWN][i]+c[KNOWN][j]+c[KNOWN][k]+c[KNOWN][l];
                if(n==0)continue;
                count++;d[KNOWN][to]=n;
                for(int f=1;f<MIN;f++)d[f][to]=c[f][i]+c[f][j]+c[f][k]+c[f][l];
                int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
                if(c[GC][i]!=0){min=(int)c[MIN][i];max=(int)c[MAX][i];}
                if(c[GC][j]!=0){min=Math.min(min,(int)c[MIN][j]);max=Math.max(max,(int)c[MAX][j]);}
                if(c[GC][k]!=0){min=Math.min(min,(int)c[MIN][k]);max=Math.max(max,(int)c[MAX][k]);}
                if(c[GC][l]!=0){min=Math.min(min,(int)c[MIN][l]);max=Math.max(max,(int)c[MAX][l]);}
                d[MIN][to]=d[GC][to]==0?UNKNOWN:min;d[MAX][to]=d[GC][to]==0?UNKNOWN:max;
                for(int f=HIST;f<HIST+H;f++)d[f][to]=c[f][i]+c[f][j]+c[f][k]+c[f][l];
            }
        }
        private static void field(byte[] bytes,int pos,int n,long value,int width){
            switch(width){
                case 1 -> bytes[pos]=(byte)value;
                case 2 -> {bytes[pos]=(byte)(value>>>8);bytes[pos+n]=(byte)value;}
                case 4 -> {bytes[pos]=(byte)(value>>>24);bytes[pos+n]=(byte)(value>>>16);
                    bytes[pos+2*n]=(byte)(value>>>8);bytes[pos+3*n]=(byte)value;}
                case 8 -> {field(bytes,pos,n,value>>>32,4);field(bytes,pos+4*n,n,value,4);}
                default -> throw new IllegalArgumentException("width");
            }
        }
        private static int channel(byte[] out,int pos,long[] values,int[] slots,int n,int width){
            for(int b=width-1;b>=0;b--){
                int shift=b*8;
                if(slots==null){
                    for(int i=0;i<n;i++)out[pos+i]=(byte)(values[i]>>>shift);
                }else{
                    for(int i=0;i<n;i++)out[pos+i]=(byte)(values[slots[i]]>>>shift);
                }
                pos+=n;
            }
            return pos;
        }
        byte[] encode()throws IOException{
            int lod=key.lod(),cw=countWidth(lod),sw=colorWidth(lod);
            boolean dense=count==N;
            int stride=stride(lod)-(dense?2:0);
            Map<FeatureDictionary.Feature,Integer> palette=new LinkedHashMap<>();
            if(lod==0)for(int slot=0;slot<N;slot++){
                Columns c=chunkAt(slot);if(c==null)continue;
                var f=c.features[columnAt(slot)];if(f!=null)palette.computeIfAbsent(f,k->palette.size()+1);
            }
            ByteArrayOutputStream head=new ByteArrayOutputStream();
            try(var out=new DataOutputStream(head)){
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(lod);out.writeInt(key.x());out.writeInt(key.z());out.writeLong(1);
                out.writeInt(palette.size());
                for(var f:palette.keySet()){out.writeUTF(f.materialKey());out.writeUTF(f.biomeKey());out.writeByte(f.terrain().ordinal());}
                out.writeInt(count);
            }
            byte[] prefix=head.toByteArray(),body=Arrays.copyOf(prefix,prefix.length+count*stride);
            int n=count,row=0,base=prefix.length;
            FeatureDictionary.Feature previous=null;int previousId=0;
            if(lod==0){
                for(int i=0;i<N;i++){
                    Columns c=chunkAt(i);int col=columnAt(i);
                    if(c==null||c.features[col]==null)continue;
                    int p=base+row++;
                    if(!dense){field(body,p,n,i,2);p+=2*n;}
                    var f=c.features[col];if(f!=previous){previous=f;previousId=palette.get(f);}
                    field(body,p,n,previousId,2);p+=2*n;
                    field(body,p,n,c.surface[col],4);p+=4*n;
                    field(body,p,n,c.ground[col],4);p+=4*n;
                    field(body,p,n,c.depth[col],2);p+=2*n;
                    field(body,p,n,c.red[col],2);p+=2*n;
                    field(body,p,n,c.green[col],2);p+=2*n;
                    field(body,p,n,c.blue[col],2);
                }
            }else{
                // Dense pages dominate fresh builds. Encode contiguous channels directly,
                // and use a single sparse slot list for partially observed pages.
                int[] slots=dense?null:new int[n];
                int pos=base;
                if(!dense){
                    for(int i=0;i<N;i++)if(data[KNOWN][i]!=0){
                        slots[row]=i;field(body,pos+row,n,i,2);row++;
                    }
                    if(row!=n)throw new IOException("Incorrect page coverage");
                    pos+=2*n;
                }
                for(int f=KNOWN;f<=WET;f++)pos=channel(body,pos,data[f],slots,n,cw);
                pos=channel(body,pos,data[SS],slots,n,8);
                pos=channel(body,pos,data[GS],slots,n,8);
                for(int f=R;f<=DEPTH;f++)pos=channel(body,pos,data[f],slots,n,sw);
                pos=channel(body,pos,data[MIN],slots,n,4);
                pos=channel(body,pos,data[MAX],slots,n,4);
                for(int f=HIST;f<HIST+H;f++)pos=channel(body,pos,data[f],slots,n,cw);
                if(pos!=body.length)throw new IOException("Incorrect page size");
            }
            return PageFrameCodec.encode(body,base,n);
        }
    }
}
