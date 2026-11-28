package com.wanstu.cartograph.v4;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.*;

public final class PageCompactionTest {
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    static final Key A=new Key(0,-1,0),B=new Key(0,0,0),C=new Key(10,-1,0);
    static final FeatureDictionary.Feature STONE=new FeatureDictionary.Feature("minecraft:stone","minecraft:plains",FeatureDictionary.Terrain.ROCK);
    static Page page(Key k,long revision,int height){
        var entry=Entry.leaf(STONE,height,height,0,15000,15000,15000);
        return new Page(k,revision,Map.of(0,entry));
    }
    static void verify(RegionPageStore s,Page a,Page b)throws IOException{
        check(s.read(A).equals(a)&&s.read(B).equals(b),"latest pages and revisions preserved");
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cartograph-compaction-");
        try{
            var store=new RegionPageStore(root,4);
            Page a=null,b=page(B,1,50);
            store.writeBatch(List.of(b));store.checkpoint();
            Path log=root.resolve("pages.cv4log"),idx=root.resolve("pages.cv4idx");
            byte[] oldIndex=Files.readAllBytes(idx);
            for(int i=1;i<=100;i++){
                a=page(A,i,60+i);
                store.writeBatch(List.of(a));
            }
            b=new Page(B,2,Map.of());store.writeBatch(List.of(b));
            long size=Files.size(log);
            check(!store.compact(size,1024*1024).compacted(),"minimum reclaim threshold");
            check(!store.compact(1,1).compacted(),"live copy cap");
            var result=store.compact(1,1024*1024);
            check(result.compacted()&&result.beforeBytes()==size&&result.afterBytes()==Files.size(log)&&result.afterBytes()<size/10,"history reclaimed");
            verify(store,a,b);
            System.out.println("Compaction fixture bytes: "+result.beforeBytes()+" -> "+result.afterBytes());
            var reopened=new RegionPageStore(root,4);
            check(reopened.startupScanBytes()==0,"replacement checkpoint");
            verify(reopened,a,b);
            // Simulate interrupted publication after the new log was moved but old index remained.
            Files.write(idx,oldIndex);
            reopened=new RegionPageStore(root,4);
            check(reopened.startupScanBytes()>0,"reject pre-compaction index");
            verify(reopened,a,b);
            try{reopened.writeBatch(List.of(page(B,2,1)));throw new AssertionError("stale tombstone revision");}
            catch(IllegalArgumentException expected){}
            reopened.checkpoint();byte[] compactIndex=Files.readAllBytes(idx);
            for(int i=101;i<=200;i++){a=page(A,i,60+i);reopened.writeBatch(List.of(a));}
            check(reopened.compact(1,1024*1024).compacted(),"repeat compaction");
            Files.write(idx,compactIndex);
            reopened=new RegionPageStore(root,4);
            check(reopened.startupScanBytes()>0,"reject older compacted generation");
            verify(reopened,a,b);
            a=page(A,201,261);reopened.writeBatch(List.of(a));reopened.checkpoint();
            long valid=Files.size(log);
            try(var out=new DataOutputStream(Files.newOutputStream(log,StandardOpenOption.APPEND))){
                out.writeInt(0x4356344c);out.writeInt(4096);out.write(new byte[10]);
            }
            reopened=new RegionPageStore(root,4);
            check(Files.size(log)==valid,"torn tail after compaction");
            verify(reopened,a,b);
            // Verification failure must leave the original log/index untouched.
            for(int i=202;i<=260;i++){a=page(A,i,60+i);reopened.writeBatch(List.of(a));}
            var raw=new PageTransactionLog(root);
            byte[] original=Files.readAllBytes(log);
            try{raw.compact(1,1024*1024,(key,rev,data)->{throw new IOException("verification failure");});
                throw new AssertionError("failed validation accepted");}
            catch(IOException expected){}
            check(Arrays.equals(original,Files.readAllBytes(log)),"failed verification preserves authoritative log");
            verify(new RegionPageStore(root,4),a,b);
            try(var list=Files.list(root)){check(list.noneMatch(p->p.getFileName().toString().startsWith(".compact-")),"no temp leak");}
            System.out.println("PASS: compaction history reclaim, unchanged revisions/tombstones, repeat generation isolation, old checkpoint recovery, torn append, validation failure and bounded work");
        }finally{ScaleTest.delete(root);}
    }
}
