package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;

/** Fresh V4 per-world/per-dimension custom markers. Mutations are atomic and revision-checked. */
public final class MarkerStore {
    public static final int MAX_ACTIVE=2048,MAX_TRASH=100;
    private static final int MAGIC=0x4356344d,VERSION=1,MAX_FILE=8*1024*1024;
    public enum Icon { POINT, HOME, FLAG, MINE, PORT, FARM }
    public record Marker(UUID id,int x,int y,int z,String name,String description,Icon icon,int color){
        public Marker{
            Objects.requireNonNull(id);Objects.requireNonNull(icon);
            name=Objects.requireNonNull(name).strip();description=Objects.requireNonNull(description);
            if(name.isEmpty()||name.length()>80||description.length()>512||
                Math.abs((long)x)>30_000_000||Math.abs((long)z)>30_000_000||Math.abs((long)y)>32768||
                (color&0xff000000)!=0||name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Invalid marker");
        }
    }
    public record Snapshot(long revision,List<Marker> active,List<Marker> trash){
        public Snapshot{active=List.copyOf(active);trash=List.copyOf(trash);}
    }
    public enum Action { UPSERT, DELETE, RESTORE, PURGE }
    public record Change(Action action,UUID id,Marker marker){
        public Change{Objects.requireNonNull(action);Objects.requireNonNull(id);
            if(action==Action.UPSERT?(marker==null||!id.equals(marker.id())):marker!=null)throw new IllegalArgumentException("Change");}
    }
    private final Path path;
    public MarkerStore(Path path){this.path=Objects.requireNonNull(path);}
    public Snapshot read()throws IOException{
        if(!Files.exists(path))return new Snapshot(0,List.of(),List.of());
        long size=Files.size(path);
        if(size<24||size>MAX_FILE)throw new IOException("Invalid marker file length");
        byte[] data=Files.readAllBytes(path);CRC32C crc=new CRC32C();crc.update(data,0,data.length-4);
        try(var in=new DataInputStream(new ByteArrayInputStream(data))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)throw new IOException("Invalid marker format");
            long revision=in.readLong();if(revision<1)throw new IOException("Marker revision");
            Set<UUID> ids=new HashSet<>();
            var active=readList(in,MAX_ACTIVE,ids);var trash=readList(in,MAX_TRASH,ids);
            if(in.readInt()!=(int)crc.getValue()||in.available()!=0)throw new IOException("Marker checksum");
            return new Snapshot(revision,active,trash);
        }catch(IllegalArgumentException e){throw new IOException("Invalid marker contents",e);}
    }
    private static List<Marker> readList(DataInputStream in,int max,Set<UUID> ids)throws IOException{
        int n=in.readInt();if(n<0||n>max)throw new IOException("Marker count");
        List<Marker> out=new ArrayList<>(n);
        for(int i=0;i<n;i++){
            var id=new UUID(in.readLong(),in.readLong());
            int x=in.readInt(),y=in.readInt(),z=in.readInt();String name=in.readUTF(),desc=in.readUTF();
            int icon=in.readUnsignedByte(),color=in.readInt();
            if(!ids.add(id)||icon>=Icon.values().length)throw new IOException("Marker identity/icon");
            out.add(new Marker(id,x,y,z,name,desc,Icon.values()[icon],color));
        }
        return out;
    }
    /** Single IO owner required. A failed mutation never acknowledges an uncommitted snapshot. */
    public Snapshot apply(long expected,Change change)throws IOException{
        Snapshot old=read();
        if(old.revision()!=expected)throw new IllegalStateException("Markers changed; reload and retry");
        List<Marker> active=new ArrayList<>(old.active()),trash=new ArrayList<>(old.trash());
        int a=index(active,change.id()),t=index(trash,change.id());
        switch(change.action()){
            case UPSERT -> {
                if(t>=0)throw new IllegalStateException("Restore deleted marker first");
                if(a>=0){if(active.get(a).equals(change.marker()))return old;active.set(a,change.marker());}
                else {if(active.size()>=MAX_ACTIVE)throw new IllegalStateException("Marker limit");active.add(change.marker());}
            }
            case DELETE -> {if(a<0)throw new IllegalStateException("Marker not found");trash.add(0,active.remove(a));if(trash.size()>MAX_TRASH)trash.removeLast();}
            case RESTORE -> {if(t<0||active.size()>=MAX_ACTIVE)throw new IllegalStateException("Cannot restore marker");active.add(trash.remove(t));}
            case PURGE -> {if(t<0)throw new IllegalStateException("Deleted marker not found");trash.remove(t);}
        }
        Snapshot next=new Snapshot(Math.incrementExact(expected),active,trash);write(next);return next;
    }
    private static int index(List<Marker> list,UUID id){for(int i=0;i<list.size();i++)if(list.get(i).id().equals(id))return i;return -1;}
    private void write(Snapshot next)throws IOException{
        var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeLong(next.revision());
            writeList(out,next.active());writeList(out,next.trash());out.flush();
            byte[] data=bytes.toByteArray();CRC32C crc=new CRC32C();crc.update(data,0,data.length);out.writeInt((int)crc.getValue());
        }
        if(bytes.size()>MAX_FILE)throw new IOException("Marker file too large");
        Path parent=path.toAbsolutePath().getParent();Files.createDirectories(parent);
        Path temp=Files.createTempFile(parent,".markers-",".tmp");
        try{
            try(var out=new FileOutputStream(temp.toFile())){bytes.writeTo(out);out.getChannel().force(true);}
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }
    private static void writeList(DataOutputStream out,List<Marker> list)throws IOException{
        out.writeInt(list.size());
        for(var m:list){
            out.writeLong(m.id().getMostSignificantBits());out.writeLong(m.id().getLeastSignificantBits());
            out.writeInt(m.x());out.writeInt(m.y());out.writeInt(m.z());out.writeUTF(m.name());out.writeUTF(m.description());
            out.writeByte(m.icon().ordinal());out.writeInt(m.color());
        }
    }
}
