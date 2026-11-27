package com.wanstu.cartograph.v4;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Separate bounded region file. No migration, revision-checked atomic changes. */
public final class NamedRegionStore {
    public static final int MAX_ACTIVE=512,MAX_TRASH=100;
    private static final int MAGIC=0x43563452,VERSION=1,MAX_BYTES=2*1024*1024;
    public record Snapshot(long revision,List<NamedRegion> active,List<NamedRegion> trash){
        public Snapshot{active=List.copyOf(active);trash=List.copyOf(trash);}
    }
    public record Change(MarkerStore.Action action,UUID id,NamedRegion region){
        public Change{Objects.requireNonNull(action);Objects.requireNonNull(id);
            if(action==MarkerStore.Action.UPSERT?(region==null||!id.equals(region.id())):region!=null)throw new IllegalArgumentException("Region change");}
    }
    private final Path path;
    public NamedRegionStore(Path path){this.path=Objects.requireNonNull(path);}
    public Snapshot read()throws IOException{
        if(!Files.exists(path))return new Snapshot(0,List.of(),List.of());
        long n=Files.size(path);if(n<28||n>MAX_BYTES)throw new IOException("Region file size");
        byte[] data=Files.readAllBytes(path);CRC32C crc=new CRC32C();crc.update(data,0,data.length-4);
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Region format");
            long rev=in.readLong();if(rev<1)throw new IOException("Region revision");
            var ids=new HashSet<UUID>();var a=readList(in,MAX_ACTIVE,ids);var t=readList(in,MAX_TRASH,ids);
            if(in.readInt()!=(int)crc.getValue()||in.available()!=0)throw new IOException("Region checksum");
            return new Snapshot(rev,a,t);
        }catch(IllegalArgumentException e){throw new IOException("Invalid region",e);}
    }
    private static List<NamedRegion> readList(DataInputStream in,int limit,Set<UUID> ids)throws IOException{
        int n=in.readInt();if(n<0||n>limit)throw new IOException("Region count");
        List<NamedRegion> out=new ArrayList<>(n);
        for(int i=0;i<n;i++){
            UUID id=new UUID(in.readLong(),in.readLong());if(!ids.add(id))throw new IOException("Duplicate region");
            out.add(new NamedRegion(id,in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readUTF(),in.readUTF(),in.readInt()));
        }
        return out;
    }
    public Snapshot apply(long expected,Change c)throws IOException{
        Snapshot old=read();if(expected!=old.revision())throw new IllegalStateException("Regions changed; reload");
        var active=new ArrayList<>(old.active());var trash=new ArrayList<>(old.trash());
        int a=index(active,c.id()),t=index(trash,c.id());
        switch(c.action()){
            case UPSERT -> {if(t>=0)throw new IllegalStateException("Restore region first");
                if(a>=0){if(active.get(a).equals(c.region()))return old;active.set(a,c.region());}
                else{if(active.size()>=MAX_ACTIVE)throw new IllegalStateException("Region limit");active.add(c.region());}}
            case DELETE -> {if(a<0)throw new IllegalStateException("Region not found");trash.addFirst(active.remove(a));if(trash.size()>MAX_TRASH)trash.removeLast();}
            case RESTORE -> {if(t<0||active.size()>=MAX_ACTIVE)throw new IllegalStateException("Cannot restore region");active.add(trash.remove(t));}
            case PURGE -> {if(t<0)throw new IllegalStateException("Region not deleted");trash.remove(t);}
        }
        var next=new Snapshot(Math.incrementExact(expected),active,trash);write(next);return next;
    }
    private static int index(List<NamedRegion> list,UUID id){for(int i=0;i<list.size();i++)if(list.get(i).id().equals(id))return i;return -1;}
    private void write(Snapshot s)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(s.revision());writeList(out,s.active());writeList(out,s.trash());
            out.flush();byte[] payload=bytes.toByteArray();CRC32C crc=new CRC32C();crc.update(payload,0,payload.length);out.writeInt((int)crc.getValue());
        }
        if(bytes.size()>MAX_BYTES)throw new IOException("Region file limit");
        Path parent=path.toAbsolutePath().getParent();Files.createDirectories(parent);Path tmp=Files.createTempFile(parent,".named-regions-",".tmp");
        try{
            try(var out=new FileOutputStream(tmp.toFile())){bytes.writeTo(out);out.getChannel().force(true);}
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
    private static void writeList(DataOutputStream out,List<NamedRegion> list)throws IOException{
        out.writeInt(list.size());for(var r:list){
            out.writeLong(r.id().getMostSignificantBits());out.writeLong(r.id().getLeastSignificantBits());
            out.writeInt(r.minX());out.writeInt(r.minZ());out.writeInt(r.maxX());out.writeInt(r.maxZ());
            out.writeUTF(r.name());out.writeUTF(r.description());out.writeInt(r.color());
        }
    }
}
