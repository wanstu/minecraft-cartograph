package com.wanstu.cartograph.v4;
import java.util.*;
public final class RetainedGroupsTest {
    static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    record Key(int region,int revision){}
    static class Resource implements AutoCloseable{boolean closed;public void close(){check(!closed,"double close");closed=true;}}
    public static void main(String[] args){
        var cache=new RetainedGroups<Key,Resource>(400,4);
        var a=new Key(1,1);var b=new Key(2,1);var c=new Key(3,1);var d=new Key(4,1);
        RetainedGroups.Builder<Key,Resource> build=k->new RetainedGroups.Item<>(new Resource(),100);
        for(var k:List.of(a,b,c,d))check(cache.ensure(k,100,Set.of(),build),"initial");
        var original=cache.get(b);var old=cache.get(a);
        for(int i=0;i<1000;i++)for(var k:List.of(a,b,c,d))check(cache.ensure(k,100,Set.of(),build),"warm");
        check(cache.builds()==4,"no warm rebuild");
        var newA=new Key(1,2);
        check(cache.ensure(newA,100,Set.of(b,c,d),build),"local change");
        check(old.closed&&cache.get(b)==original&&cache.builds()==5,"only one group replaced");
        check(!cache.ensure(new Key(5,1),100,Set.of(newA,b,c,d),build),"bounded protected");
        cache.retainValid(k->k.region()!=2);check(original.closed&&cache.bytes()==300,"invalid dependency removal");
        check(!cache.ensure(new Key(8,1),401,Set.of(),build),"oversize");
        cache.close();check(cache.bytes()==0&&cache.size()==0,"cleanup");
        System.out.println("PASS: 1000 warm iterations, local dependency replacement, protected eviction, bounded bytes, cleanup");
    }
}
