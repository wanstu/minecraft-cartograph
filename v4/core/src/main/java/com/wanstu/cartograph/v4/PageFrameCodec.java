package com.wanstu.cartograph.v4;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Lossless byte-plane frame: base + exact variable-width offsets, with full raw CRC32C. */
final class PageFrameCodec {
    private static final int MAX=4*1024*1024, HEADER=20, MAX_PLANES=128;
    private static final ThreadLocal<Scratch> SCRATCH=ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch{
        byte[] packed=new byte[128*1024];
    }
    static byte[] encode(byte[] raw,int prefix,int count)throws IOException{
        if(raw.length<1||raw.length>MAX||prefix<0||prefix>raw.length||count<0||count>1024)
            throw new IOException("Frame bounds");
        int numeric=raw.length-prefix;
        if(count==0?numeric!=0:numeric==0||numeric%count!=0)throw new IOException("Frame planes");
        int planes=count==0?0:numeric/count;
        if(planes>MAX_PLANES)throw new IOException("Frame plane count");
        return encodeBits(raw,prefix,count,planes);
    }
    static byte[] decode(byte[] data)throws IOException{
        if(data.length<=HEADER)throw new IOException("Frame header");
        ByteBuffer header=ByteBuffer.wrap(data);
        int rawLength=header.getInt(),checksum=header.getInt(),prefix=header.getInt(),count=header.getInt(),packedLength=header.getInt();
        if(rawLength<1||rawLength>MAX||prefix<0||prefix>rawLength||count<0||count>1024)
            throw new IOException("Frame bounds");
        int numeric=rawLength-prefix;
        if(count==0?numeric!=0:numeric==0||numeric%count!=0)throw new IOException("Frame planes");
        int planes=count==0?0:numeric/count;
        return decodeBits(data,rawLength,checksum,prefix,count,planes,packedLength);
    }
    private static byte[] encodeBits(byte[] raw,int prefix,int count,int planes)throws IOException{
        Scratch scratch=SCRATCH.get();
        int required=HEADER+raw.length+planes*2;
        if(scratch.packed.length<required)scratch.packed=new byte[required];
        byte[] out=scratch.packed;
        System.arraycopy(raw,0,out,HEADER,prefix);
        int pos=HEADER+prefix+planes*2;
        for(int i=0;i<planes;i++){
            int from=prefix+i*count;
            int lo=raw[from]&255,hi=lo;
            if(count>1&&Arrays.mismatch(raw,from,from+count-1,raw,from+1,from+count)!=-1){
                for(int j=from+1;j<from+count;j++){
                    int v=raw[j]&255;lo=Math.min(lo,v);hi=Math.max(hi,v);
                    if(hi-lo>=128)break; // any further values still need eight bits
                }
            }
            int width=32-Integer.numberOfLeadingZeros(hi-lo);
            int meta=HEADER+prefix+i*2;
            out[meta]=(byte)width;out[meta+1]=(byte)lo;
            if(width==0)continue;
            if(width==8){out[meta+1]=0;System.arraycopy(raw,from,out,pos,count);pos+=count;continue;}
            int j=from,end=from+count;
            for(;j+8<=end;j+=8){
                long word=(long)((raw[j]&255)-lo)
                    |((long)((raw[j+1]&255)-lo)<<width)
                    |((long)((raw[j+2]&255)-lo)<<(2*width))
                    |((long)((raw[j+3]&255)-lo)<<(3*width))
                    |((long)((raw[j+4]&255)-lo)<<(4*width))
                    |((long)((raw[j+5]&255)-lo)<<(5*width))
                    |((long)((raw[j+6]&255)-lo)<<(6*width))
                    |((long)((raw[j+7]&255)-lo)<<(7*width));
                for(int k=0;k<width;k++){out[pos++]=(byte)word;word>>>=8;}
            }
            int bits=0,buffer=0;
            for(;j<end;j++){
                buffer|=((raw[j]&255)-lo)<<bits;bits+=width;
                if(bits>=8){out[pos++]=(byte)buffer;buffer>>>=8;bits-=8;}
            }
            if(bits!=0)out[pos++]=(byte)buffer;
        }
        CRC32C crc=new CRC32C();crc.update(raw);
        ByteBuffer.wrap(out).putInt(raw.length).putInt((int)crc.getValue()).putInt(prefix).putInt(count).putInt(pos-HEADER);
        return Arrays.copyOf(out,pos);
    }
    private static byte[] decodeBits(byte[] data,int rawLength,int checksum,int prefix,int count,int planes,int length)throws IOException{
        if(planes>MAX_PLANES||length<prefix+planes*2||length>rawLength+planes*2||data.length!=HEADER+length)
            throw new IOException("Bit frame length");
        byte[] raw=new byte[rawLength];
        System.arraycopy(data,HEADER,raw,0,prefix);
        int pos=HEADER+prefix+planes*2;
        for(int i=0;i<planes;i++){
            int meta=HEADER+prefix+i*2,width=data[meta]&255,lo=data[meta+1]&255,to=prefix+i*count;
            if(width>8)throw new IOException("Bit width");
            int bytes=(count*width+7)/8;
            if(bytes>data.length-pos)throw new IOException("Bit plane length");
            if(width==0){Arrays.fill(raw,to,to+count,(byte)lo);continue;}
            if(width==8){if(lo!=0)throw new IOException("Bit plane base");System.arraycopy(data,pos,raw,to,count);pos+=count;continue;}
            int bits=0,buffer=0,mask=(1<<width)-1;
            for(int j=to;j<to+count;j++){
                if(bits<width){buffer|=(data[pos++]&255)<<bits;bits+=8;}
                int value=lo+(buffer&mask);
                if(value>255)throw new IOException("Bit value overflow");
                raw[j]=(byte)value;buffer>>>=width;bits-=width;
            }
            if(buffer!=0)throw new IOException("Bit plane padding");
        }
        if(pos!=data.length)throw new IOException("Bit frame trailing data");
        CRC32C crc=new CRC32C();crc.update(raw);
        if((int)crc.getValue()!=checksum)throw new IOException("Page checksum");
        return raw;
    }
}
