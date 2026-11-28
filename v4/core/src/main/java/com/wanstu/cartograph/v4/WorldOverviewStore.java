package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

/** Disposable, style-independent coarse pages. The shard logs remain authoritative.
 * Single geography owner; invalidate and force existing files BEFORE changing the source.
 */
final class WorldOverviewStore {
    static final int MIN_LOD=6;
    private static final int MAX_BYTES=4*1024*1024+32*1024;
    private final Path root;
    WorldOverviewStore(Path world){root=world.resolve("overview-v1");}
    static boolean supports(Key key){return key.lod()>=MIN_LOD;}
    private Path path(Key key){
        if(!supports(key))throw new IllegalArgumentException("Overview LOD");
        return root.resolve(key.lod()+"."+key.x()+"."+key.z()+".cv4page");
    }
    Page read(Key key)throws IOException{
        Path file=path(key);
        try(var in=Files.newInputStream(file)){
            byte[] data=in.readNBytes(MAX_BYTES+1);
            if(data.length==0)return null; // durable invalidation
            if(data.length>MAX_BYTES)throw new IOException("Overview size");
            return RegionPageStore.decode(data,key);
        }catch(NoSuchFileException absent){return null;}
    }
    void write(Page page)throws IOException{
        if(page.size()==0)return;
        Path file=path(page.key());
        byte[] data=RegionPageStore.encode(page);
        if(data.length>MAX_BYTES)throw new IOException("Overview size");
        Files.createDirectories(root);
        Path tmp=Files.createTempFile(root,".overview-",".tmp");
        try{
            try(var out=new RandomAccessFile(tmp.toFile(),"rw")){
                out.write(data);out.getChannel().force(true);
            }
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(tmp);}
    }
    /** A zero-length file cannot decode as a page. Keep the invalidation at its existing
     * directory entry rather than depending on a durable directory deletion.
     */
    void invalidate(Key key)throws IOException{
        if(!supports(key))return;
        try(var channel=FileChannel.open(path(key),StandardOpenOption.WRITE)){
            if(channel.size()==0)return;
            channel.truncate(0);channel.force(true);
        }catch(NoSuchFileException absent){/* no cached overview exists */}
    }
}
