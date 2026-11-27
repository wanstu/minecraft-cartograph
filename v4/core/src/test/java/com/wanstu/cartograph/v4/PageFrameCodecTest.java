package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public final class PageFrameCodecTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void reject(byte[] bytes)throws Exception{
        try{PageFrameCodec.decode(bytes);throw new AssertionError("Accepted invalid frame");}
        catch(IOException expected){}
    }
    public static void main(String[] args)throws Exception{
        Random random=new Random(517);
        int cases=0;
        for(int n:new int[]{1,2,3,7,8,9,15,16,17,63,64,65,127,255,1023,1024}){
            for(int bits=0;bits<=8;bits++){
                int prefix=36,planes=5;
                byte[] raw=new byte[prefix+n*planes];
                random.nextBytes(raw);
                for(int plane=0;plane<planes;plane++){
                    int range=(1<<bits)-1,base=random.nextInt(256-range);
                    for(int i=0;i<n;i++)raw[prefix+plane*n+i]=(byte)(base+random.nextInt(range+1));
                    if(n>=2){
                        raw[prefix+plane*n]=(byte)base;
                        raw[prefix+plane*n+1]=(byte)(base+range);
                    }
                }
                byte[] frame=PageFrameCodec.encode(raw,prefix,n);
                check(Arrays.equals(raw,PageFrameCodec.decode(frame)),"Roundtrip "+n+" width "+bits);
                check(Arrays.equals(frame,PageFrameCodec.encode(raw,prefix,n)),"Determinism");
                reject(Arrays.copyOf(frame,frame.length-1));
                byte[] broken=frame.clone();broken[4]^=1;reject(broken);
                broken=frame.clone();broken[20+prefix]=(byte)9;reject(broken);
                broken=frame.clone();ByteBuffer.wrap(broken).putInt(16,Integer.MAX_VALUE);reject(broken);
                cases++;
            }
        }
        byte[] emptyPage=new byte[36];random.nextBytes(emptyPage);
        check(Arrays.equals(emptyPage,PageFrameCodec.decode(PageFrameCodec.encode(emptyPage,36,0))),"Empty page");
        System.out.println("PASS: "+cases+" byte-plane roundtrips, every bit width, odd lengths, deterministic encoding, malformed headers and checksum");
    }
}
