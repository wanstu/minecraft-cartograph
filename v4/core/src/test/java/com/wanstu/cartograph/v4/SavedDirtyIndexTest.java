package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.ActiveChunkScheduler.*;

public final class SavedDirtyIndexTest {
    static void check(boolean b,String label){if(!b)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-dirty-index-");
        try{
            Position a=new Position(-5,4),b=new Position(10000,-10000);
            SavedDirtyIndex index=new SavedDirtyIndex(root);
            var initial=index.mark(List.of(a,b));
            check(index.size()==2&&index.flushes()==1,"batch force");
            var old=initial.get(0);var newer=index.mark(List.of(a)).get(0);
            check(!index.acknowledge(old)&&index.current(newer),"stale acknowledgement");
            index=new SavedDirtyIndex(root);
            check(index.size()==2&&index.current(newer),"restart pending");
            check(index.acknowledge(newer)&&index.size()==1,"matching durable acknowledgement");
            index=new SavedDirtyIndex(root);
            check(index.size()==1&&index.current(initial.get(1)),"restart clear");
            Path file=root.resolve("dirty.cv4log");long length=Files.size(file);
            try(var out=new DataOutputStream(Files.newOutputStream(file,StandardOpenOption.APPEND))){
                out.writeInt(0x43563444);out.writeInt(21);out.write(new byte[10]);
            }
            index=new SavedDirtyIndex(root);
            check(Files.size(file)==length&&index.size()==1,"torn append");
            // Scheduler retains an unloaded revision until receipt is actually durable.
            var scheduler=new ActiveChunkScheduler(t->null,100,10,4);
            var queue=new DirtyHandoffQueue(2);
            scheduler.changed(1,1);scheduler.changed(2,2);scheduler.changed(3,3);
            check(scheduler.drainInactiveAsync(3,queue)==0&&queue.size()==2&&scheduler.metrics().inactivePending()==3,"bounded queue, no early removal");
            scheduler.changed(1,1);
            check(queue.drain(index,256)==2,"durable batch");
            check(scheduler.drainInactiveAsync(3,queue)==1&&scheduler.metrics().inactivePending()==2,"old receipt preserves newer change");
            check(queue.size()==2,"fair retry");
            queue.drain(index,256);
            check(scheduler.drainInactiveAsync(3,queue)==2&&scheduler.metrics().inactivePending()==0,"durable transfer");
            index=new SavedDirtyIndex(root);
            check(index.size()==4,"all transferred work survives restart");
            var current=index.peek(256);
            check(current.stream().anyMatch(w->w.position().equals(new Position(1,1))),"latest pending survived");
            // Reload before acknowledgement does not make an old callback mark new active state clean.
            scheduler.changed(7,7);scheduler.drainInactiveAsync(1,queue);
            scheduler.loaded(7,7,false);
            queue.drain(index,256);
            check(scheduler.metrics().queuedDirty()==1&&scheduler.metrics().inactivePending()==0,"reload remains dirty");
            scheduler.loaded(9,9,true);
            var activeTicket=scheduler.poll();
            scheduler.prepareShutdown();
            check(scheduler.metrics().active()==0&&scheduler.metrics().inactivePending()==2,"shutdown transfers dirty and reserved chunks");
            scheduler.finish(activeTicket,CapturePump.Outcome.COMMITTED);
            check(scheduler.metrics().inactivePending()==2,"late shutdown receipt preserves work");
            scheduler.drainInactiveAsync(2,queue);queue.drain(index,256);
            check(scheduler.drainInactiveAsync(2,queue)==2,"shutdown handoff confirmed");
            index=new SavedDirtyIndex(root);
            var batch=index.peek(256);
            long before=index.flushes();
            check(index.acknowledgeBatch(List.of(batch.get(0),batch.get(0),batch.get(1)))==2&&index.flushes()==before+1,"deduplicated durable acknowledgement batch");
            index=new SavedDirtyIndex(root);
            check(!index.current(batch.get(0))&&!index.current(batch.get(1)),"batch acknowledgement survives restart");
            queue.closeInput();
            scheduler.changed(8,8);
            check(scheduler.drainInactiveAsync(1,queue)==0&&scheduler.metrics().inactivePending()==1,"closed queue retains pending");
            // Completed corrupt frames fail closed instead of treating the region as clean.
            try(var out=new RandomAccessFile(file.toFile(),"rw")){
                out.seek(12);int v=out.readUnsignedByte();out.seek(12);out.writeByte(v^1);
            }
            try{new SavedDirtyIndex(root);throw new AssertionError("corrupt frame accepted");}
            catch(IOException expected){}
            System.out.println("PASS: durable batched handoff, restart, stale tokens, torn append, backpressure, fairness, reload race, shutdown handoff, batched acknowledgements and corruption rejection");
        }finally{ScaleTest.delete(root);}
    }
}
