package com.wanstu.cartograph.v4;
import java.nio.file.*;
import java.util.*;
import static com.wanstu.cartograph.v4.StructureIndex.*;
public final class StructureIndexTest{
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("cv4-structures");
        try{
            var s=new StructureIndex(root);var a=new Start("minecraft:village_plains",-1,0);var b=new Start("mod:port",0,0);
            var view=new Bounds(-2,-2,2,2);
            check(!s.replace(4,4,List.of())&&Files.list(root).count()==0,"empty no write");
            check(s.replace(-1,0,List.of(a,a))&&s.replace(0,0,List.of(b)),"cross-region");
            check(!s.replace(-1,0,List.of(a)),"dedup no-op");
            check(new StructureIndex(root).query(view).starts().equals(List.of(a,b)),"restart and negative boundary");
            check(s.replace(-1,0,List.of())&&s.query(view).starts().equals(List.of(b)),"authoritative removal");
            check(s.query(new Bounds(-10000,-10000,10000,10000)).limited(),"bounded wide query");
            var p=root.resolve("r.0.0.cv4s");byte[] corrupt=Files.readAllBytes(p);corrupt[corrupt.length-1]^=1;Files.write(p,corrupt);
            try{new StructureIndex(root).query(view);throw new AssertionError("corruption");}catch(java.io.IOException expected){}
            System.out.println("PASS: sparse start index, duplicate/no-op, negative boundary, reopen, removal, bounded query and CRC");
        }finally{ScaleTest.delete(root);}
    }
}
