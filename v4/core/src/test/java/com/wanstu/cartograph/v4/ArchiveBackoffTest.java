package com.wanstu.cartograph.v4;
public final class ArchiveBackoffTest{
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        var b=new ArchiveBackoff();
        check(b.due(1,false),"first scan");b.submitted(1);
        b.completed(2,true,false,false,false);
        check(!b.due(50,false),"idle sleep");
        b.wake();check(b.due(50,false),"durable handoff wakes idle scan");
        b.submitted(50);
        check(!b.due(51,true),"minimum period even with parked wake");
        b.wake();b.completed(52,true,false,false,false);
        check(b.due(52,false),"wake during in-flight scan survives receipt");
        b.submitted(52);b.completed(54,true,false,false,false);
        check(!b.due(55,false)&&b.due(55,true),"parked unload wakes scan");
        b.submitted(55);b.failed(56);b.wake();
        check(!b.due(255,true)&&b.due(256,true),"failure backoff not bypassed");
        b.submitted(256);b.completed(258,true,true,true,false);
        check(!b.due(259,false)&&b.due(260,false),"normal progress");
        System.out.println("PASS: durable handoff and parked unload wakeups, in-flight completion, bounded pacing and failure backoff");
    }
}
