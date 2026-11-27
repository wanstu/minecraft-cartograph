package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32C;
import static com.wanstu.cartograph.v4.ActiveChunkScheduler.Position;

/**
 * Per-dimension durable work index, owned exclusively by the geographic IO worker.
 * No Minecraft access. A saved-world reader must separately validate source identity
 * and serialize its geographic commits against live captures.
 */
public final class SavedDirtyIndex {
    private static final int MAGIC=0x43563444, END=0x444f4e45, MAX_BATCH=256, RECORD=17;
    public record Work(Position position,long token){}
    private final Path file;
    private final Map<Position,Long> pending=new LinkedHashMap<>();
    private final Thread owner=Thread.currentThread();
    private long sequence,flushes;
    private boolean failed;
    public SavedDirtyIndex(Path directory)throws IOException{
        Files.createDirectories(directory);file=directory.resolve("dirty.cv4log");
        if(Files.notExists(file))Files.createFile(file);
        recover();
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Dirty index IO owner");}
    private void ready()throws IOException{owner();if(failed)throw new IOException("Reopen dirty index after failed transaction");}
    public int size(){owner();return pending.size();}
    public long flushes(){owner();return flushes;}
    public boolean current(Work work){owner();return Objects.equals(pending.get(work.position),work.token);}
    public List<Work> peek(int max){
        owner();if(max<0||max>MAX_BATCH)throw new IllegalArgumentException("Batch");
        List<Work> result=new ArrayList<>();
        for(var e:pending.entrySet()){if(result.size()==max)break;result.add(new Work(e.getKey(),e.getValue()));}
        return List.copyOf(result);
    }
    /** Retry fairness only; persisted tokens remain unchanged. */
    void rotate(Work w){
        owner();if(current(w)){pending.remove(w.position);pending.put(w.position,w.token);}
    }
    /** Acknowledgement is returned only after the batch is forced to disk. */
    public List<Work> mark(List<Position> positions)throws IOException{
        ready();Objects.requireNonNull(positions);
        if(positions.size()>MAX_BATCH)throw new IllegalArgumentException("Batch");
        List<Work> result=new ArrayList<>(positions.size());
        long token=sequence;
        for(Position p:positions){Objects.requireNonNull(p);token=Math.incrementExact(token);result.add(new Work(p,token));}
        append(result,false);
        for(Work w:result){pending.remove(w.position);pending.put(w.position,w.token);}
        sequence=token;return List.copyOf(result);
    }
    /** Only after matching geographic data is committed durably. Old receipts are harmless. */
    public boolean acknowledge(Work work)throws IOException{
        return acknowledgeBatch(List.of(work))==1;
    }
    /** Caller has already durably committed each matching snapshot. One force per batch. */
    public int acknowledgeBatch(List<Work> works)throws IOException{
        ready();Objects.requireNonNull(works);
        if(works.size()>MAX_BATCH)throw new IllegalArgumentException("Batch");
        Set<Position> seen=new HashSet<>();
        List<Work> matched=new ArrayList<>();
        for(Work work:works){
            Objects.requireNonNull(work);
            if(current(work)&&seen.add(work.position))matched.add(work);
        }
        append(matched,true);
        for(Work work:matched)pending.remove(work.position);
        return matched.size();
    }
    private static int crc(byte[] body){CRC32C crc=new CRC32C();crc.update(body);return (int)crc.getValue();}
    private void append(List<Work> records,boolean clear)throws IOException{
        if(records.isEmpty())return;
        byte[] body=new byte[4+RECORD*records.size()];
        ByteBuffer b=ByteBuffer.wrap(body).putInt(records.size());
        for(Work w:records)b.put((byte)(clear?1:0)).putInt(w.position.x()).putInt(w.position.z()).putLong(w.token);
        failed=true;
        try(var out=new RandomAccessFile(file.toFile(),"rw")){
            out.seek(out.length());out.writeInt(MAGIC);out.writeInt(body.length);out.write(body);
            out.writeInt(crc(body));out.writeInt(END);out.getChannel().force(true);
            flushes++;
        }
        failed=false;
    }
    private void recover()throws IOException{
        try(var in=new RandomAccessFile(file.toFile(),"rw")){
            long end=in.length(),pos=0;
            while(pos<end){
                in.seek(pos);
                if(end-pos<8){in.setLength(pos);in.getChannel().force(true);break;}
                if(in.readInt()!=MAGIC)throw new IOException("Dirty index magic");
                int length=in.readInt();
                if(length<4+RECORD||length>4+MAX_BATCH*RECORD||(length-4)%RECORD!=0)
                    throw new IOException("Dirty index frame length");
                if(end-pos<16L+length){in.setLength(pos);in.getChannel().force(true);break;}
                byte[] body=new byte[length];in.readFully(body);
                if(in.readInt()!=crc(body)||in.readInt()!=END)throw new IOException("Dirty index checksum");
                ByteBuffer b=ByteBuffer.wrap(body);int count=b.getInt();
                if(count!=(length-4)/RECORD)throw new IOException("Dirty index count");
                for(int i=0;i<count;i++){
                    int op=b.get()&255;Position p=new Position(b.getInt(),b.getInt());long token=b.getLong();
                    if(op==0){
                        if(token<=sequence)throw new IOException("Dirty index token order");
                        sequence=token;pending.remove(p);pending.put(p,token);
                    }else if(op==1){
                        if(!Objects.equals(pending.get(p),token))throw new IOException("Dirty index acknowledgement mismatch");
                        pending.remove(p);
                    }else throw new IOException("Dirty index operation");
                }
                pos+=16L+length;
            }
        }
    }
}
