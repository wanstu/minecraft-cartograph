package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Bounded, lazy discovery of already saved chunks. One region header resident at a time. */
public final class SavedArchiveCursor implements AutoCloseable {
    public record Candidate(Path path,int regionX,int regionZ,AnvilRegionReader.SavedChunk chunk){}
    public record Step(List<Candidate> candidates,int regionsOpened,boolean exhausted){
        public Step{candidates=List.copyOf(candidates);}
    }
    private final DirectoryStream<Path> directory;
    private final Iterator<Path> files;
    private final Thread owner=Thread.currentThread();
    private Iterator<AnvilRegionReader.SavedChunk> chunks=Collections.emptyIterator();
    private Path current;
    private int rx,rz;
    private boolean exhausted,closed;
    public SavedArchiveCursor(Path regionDirectory)throws IOException{
        directory=Files.newDirectoryStream(regionDirectory,"r.*.mca");
        files=directory.iterator();
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Archive IO owner");}
    /** At most maxRegions directory entries inspected and maxChunks candidates returned. */
    public Step step(int maxRegions,int maxChunks)throws IOException{
        owner();if(closed)throw new IllegalStateException("Closed archive cursor");
        if(maxRegions<1||maxRegions>32||maxChunks<1||maxChunks>1024)throw new IllegalArgumentException("Discovery budget");
        List<Candidate> result=new ArrayList<>();
        int inspected=0,opened=0;
        try{
            while(result.size()<maxChunks){
                if(chunks.hasNext()){result.add(new Candidate(current,rx,rz,chunks.next()));continue;}
                if(exhausted||inspected>=maxRegions)break;
                if(!files.hasNext()){exhausted=true;break;}
                Path next=files.next();inspected++;
                String[] parts=next.getFileName().toString().split("\\.");
                if(parts.length!=4||!parts[0].equals("r")||!parts[3].equals("mca")||!Files.isRegularFile(next))continue;
                int x,z;
                try{x=Integer.parseInt(parts[1]);z=Integer.parseInt(parts[2]);}
                catch(NumberFormatException e){continue;}
                if(!next.getFileName().toString().equals("r."+x+"."+z+".mca"))continue;
                try(var reader=new AnvilRegionReader(next,x,z)){
                    chunks=reader.discover().iterator();
                }
                current=next;rx=x;rz=z;opened++;
            }
        }catch(DirectoryIteratorException e){throw e.getCause();}
        return new Step(result,opened,exhausted&&!chunks.hasNext());
    }
    @Override public void close()throws IOException{owner();if(!closed){closed=true;directory.close();}}
}
