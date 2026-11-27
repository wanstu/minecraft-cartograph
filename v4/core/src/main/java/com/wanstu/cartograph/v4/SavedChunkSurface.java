package com.wanstu.cartograph.v4;

import java.io.*;
import java.util.*;

/** Bounded decoder of modern 1.21.x chunk NBT, without Minecraft classes. */
public final class SavedChunkSurface {
    private SavedChunkSurface(){}
    /** Normal protochunk state; not a corrupt or fully generated map source. */
    public static final class NotFullyGeneratedException extends IOException{
        public NotFullyGeneratedException(String status){super("Chunk not fully generated: "+status);}
    }
    public record Column(String material,String biome,int surfaceY,int reliefY,int oceanFloorY){}
    public record Snapshot(int x,int z,int dataVersion,Column[] columns){
        public Snapshot{columns=columns.clone();}
        @Override public Column[] columns(){return columns.clone();}
    }
    private static final int LIMIT=32*1024*1024, MAX_DEPTH=64, MAX_NODES=1_000_000;
    private static final Set<String> ROOT=Set.of("DataVersion","xPos","zPos","Status","Heightmaps","sections");
    private static final Set<String> HEIGHTS=Set.of("WORLD_SURFACE","OCEAN_FLOOR","MOTION_BLOCKING_NO_LEAVES");
    private static final Set<String> SECTION=Set.of("Y","block_states","biomes");
    private static final Set<String> PALETTE=Set.of("palette","data");
    private record Bits(long[] data,int bits,int size){
        Bits{
            if(bits<1||bits>32||size<1||data.length!=(size+64/bits-1)/(64/bits))
                throw new IllegalArgumentException("Packed array length");
        }
        int at(int i){
            if(i<0||i>=size)throw new IllegalArgumentException("Packed index");
            int per=64/bits;return (int)((data[i/per]>>>((i%per)*bits))&((1L<<bits)-1));
        }
    }
    private record Section(String[] states,Bits stateIndices,String[] biomes,Bits biomeIndices){
        String state(int x,int y,int z)throws IOException{
            if(states==null)return "minecraft:air";
            int index=stateIndices==null?0:stateIndices.at(((y&15)*16+z)*16+x);
            if(index<0||index>=states.length)throw new IOException("Block palette index");
            return states[index];
        }
        String biome(int x,int y,int z)throws IOException{
            if(biomes==null)return null;
            int index=biomeIndices==null?0:biomeIndices.at((((y&15)>>2)*4+(z>>2))*4+(x>>2));
            if(index<0||index>=biomes.length)throw new IOException("Biome palette index");
            return biomes[index];
        }
    }
    public static Snapshot decode(byte[] nbt,int expectedX,int expectedZ,int minY,int height)throws IOException{
        if(nbt.length>LIMIT||height<16||height>4096||height%16!=0||minY%16!=0)
            throw new IOException("Chunk bounds");
        try(var in=new DataInputStream(new ByteArrayInputStream(nbt))){
            int maxY=Math.addExact(minY,height);
            Reader reader=new Reader(in);
            if(in.readUnsignedByte()!=10)throw new IOException("NBT root");
            in.readUTF();
            Map<String,Object> root=reader.compound(0,ROOT);
            if(in.read()!=-1)throw new IOException("NBT trailing data");
            if(number(root,"xPos")!=expectedX||number(root,"zPos")!=expectedZ)throw new IOException("Chunk coordinate mismatch");
            int version=number(root,"DataVersion");
            if(version<3953||version>3955)throw new IOException("Unsupported 1.21/1.21.1 chunk DataVersion "+version);
            Object status=root.get("Status");
            if(!(status instanceof String statusKey))throw new IOException("Missing chunk generation status");
            if(!"minecraft:full".equals(statusKey)&&!"full".equals(statusKey))throw new NotFullyGeneratedException(statusKey);
            Map<String,Object> hm=compound(root.get("Heightmaps"));
            int width=32-Integer.numberOfLeadingZeros(height);
            Bits surface=new Bits(longs(hm,"WORLD_SURFACE"),width,256);
            Bits relief=new Bits(longs(hm,"MOTION_BLOCKING_NO_LEAVES"),width,256);
            Bits floor=new Bits(longs(hm,"OCEAN_FLOOR"),width,256);
            Map<Integer,Section> sections=new HashMap<>();
            for(Object o:list(root.get("sections"))){
                Map<String,Object> s=compound(o);int sy=number(s,"Y");
                if(sy<Math.floorDiv(minY,16)||sy>=Math.floorDiv(maxY,16))continue;
                Map<String,Object> block=optionalCompound(s.get("block_states")),biome=optionalCompound(s.get("biomes"));
                String[] states=null,biomes=null;Bits si=null,bi=null;
                if(block!=null){
                    List<?> palette=list(block.get("palette"));
                    if(palette.isEmpty()||palette.size()>4096)throw new IOException("Block palette size");
                    states=new String[palette.size()];
                    for(int i=0;i<states.length;i++)states[i]=stateKey(compound(palette.get(i)));
                    if(states.length>1)si=new Bits(longs(block,"data"),Math.max(4,bits(states.length)),4096);
                }
                if(biome!=null){
                    List<?> palette=list(biome.get("palette"));
                    if(palette.isEmpty()||palette.size()>64)throw new IOException("Biome palette size");
                    biomes=new String[palette.size()];
                    for(int i=0;i<biomes.length;i++){
                        if(!(palette.get(i) instanceof String key))throw new IOException("Biome key");
                        biomes[i]=key;
                    }
                    if(biomes.length>1)bi=new Bits(longs(biome,"data"),bits(biomes.length),64);
                }
                if(sections.put(sy,new Section(states,si,biomes,bi))!=null)throw new IOException("Duplicate section");
            }
            Column[] columns=new Column[256];
            for(int i=0;i<256;i++){
                int top=surface.at(i),r=relief.at(i),f=floor.at(i);
                if(top<0||top>height||r<0||r>height||f<0||f>height)throw new IOException("Heightmap range");
                if(top==0)continue;
                int y=minY+top-1,x=i&15,z=i>>>4;
                Section s=sections.get(Math.floorDiv(y,16));
                if(s==null||s.states==null)throw new IOException("Missing surface block section");
                String state=s.state(x,y,z);
                String biome=s.biome(x,y,z);
                if(biome==null)throw new IOException("Missing surface biome");
                columns[i]=new Column(state,biome,y,r==0?GeographicPyramid.UNKNOWN_HEIGHT:minY+r-1,
                    f==0?GeographicPyramid.UNKNOWN_HEIGHT:minY+f-1);
            }
            return new Snapshot(expectedX,expectedZ,version,columns);
        }catch(IllegalArgumentException|ArithmeticException e){throw new IOException("Invalid chunk NBT",e);}
    }
    private static int bits(int n){return 32-Integer.numberOfLeadingZeros(n-1);}
    private static int number(Map<String,Object> m,String k)throws IOException{
        if(m.get(k) instanceof Number n)return n.intValue();
        throw new IOException("Missing numeric "+k);
    }
    private static long[] longs(Map<String,Object> m,String k)throws IOException{
        if(m.get(k) instanceof long[] v)return v;
        throw new IOException("Missing long array "+k);
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> compound(Object o)throws IOException{
        if(o instanceof Map<?,?> m)return (Map<String,Object>)m;
        throw new IOException("Missing NBT compound");
    }
    private static Map<String,Object> optionalCompound(Object o)throws IOException{return o==null?null:compound(o);}
    private static List<?> list(Object o)throws IOException{
        if(o instanceof List<?> l)return l;
        throw new IOException("Missing NBT list");
    }
    private static String stateKey(Map<String,Object> state)throws IOException{
        if(!(state.get("Name") instanceof String name)||name.isBlank())throw new IOException("Block state name");
        var properties=optionalCompound(state.get("Properties"));
        if(properties==null||properties.isEmpty())return name;
        StringJoiner out=new StringJoiner(",",name+"[","]");
        for(var e:new TreeMap<>(properties).entrySet()){
            if(!(e.getValue() instanceof String value))throw new IOException("State property");
            out.add(e.getKey()+"="+value);
        }
        return out.toString();
    }
    private static final class Reader{
        final DataInputStream in;int nodes;
        Reader(DataInputStream in){this.in=in;}
        private void visit(int depth)throws IOException{
            if(depth>MAX_DEPTH||++nodes>MAX_NODES)throw new IOException("NBT complexity limit");
        }
        private int count(int size)throws IOException{
            int n=in.readInt();if(n<0||n>LIMIT/size||n>(in.available()/size))throw new IOException("NBT array bounds");return n;
        }
        Map<String,Object> compound(int depth,Set<String> keep)throws IOException{
            visit(depth);Map<String,Object> m=new HashMap<>();
            while(true){
                int type=in.readUnsignedByte();if(type==0)return m;
                String key=in.readUTF();
                if(keep!=null&&!keep.contains(key)){skip(type,depth+1);continue;}
                Object value;
                if(depth==0&&key.equals("Heightmaps")&&type==10)value=compound(depth+1,HEIGHTS);
                else if(depth==0&&key.equals("sections")&&type==9)value=sections(depth+1);
                else if(keep==SECTION&&(key.equals("block_states")||key.equals("biomes"))&&type==10)value=compound(depth+1,PALETTE);
                else value=value(type,depth+1);
                if(m.put(key,value)!=null)throw new IOException("Duplicate NBT key");
            }
        }
        private List<?> sections(int depth)throws IOException{
            visit(depth);int type=in.readUnsignedByte(),n=count(1);
            if(type!=10||n>512)throw new IOException("Section list");
            List<Object> l=new ArrayList<>(n);
            for(int i=0;i<n;i++)l.add(compound(depth+1,SECTION));
            return l;
        }
        private Object value(int type,int depth)throws IOException{
            visit(depth);
            return switch(type){
                case 1 -> in.readByte();case 2 -> in.readShort();case 3 -> in.readInt();case 4 -> in.readLong();
                case 5 -> in.readFloat();case 6 -> in.readDouble();
                case 7 -> {byte[] b=new byte[count(1)];in.readFully(b);yield b;}
                case 8 -> in.readUTF();
                case 9 -> {
                    int t=in.readUnsignedByte(),n=count(1);
                    if(n>MAX_NODES||(t==0&&n!=0))throw new IOException("NBT list size");
                    List<Object> l=new ArrayList<>(n);
                    for(int i=0;i<n;i++)l.add(value(t,depth+1));yield l;
                }
                case 10 -> compound(depth+1,null);
                case 11 -> {int[] a=new int[count(4)];for(int i=0;i<a.length;i++)a[i]=in.readInt();yield a;}
                case 12 -> {long[] a=new long[count(8)];for(int i=0;i<a.length;i++)a[i]=in.readLong();yield a;}
                default -> throw new IOException("Invalid NBT type "+type);
            };
        }
        private void skip(int type,int depth)throws IOException{
            visit(depth);
            switch(type){
                case 1 -> in.skipNBytes(1);case 2 -> in.skipNBytes(2);
                case 3,5 -> in.skipNBytes(4);case 4,6 -> in.skipNBytes(8);
                case 7 -> in.skipNBytes(count(1));case 8 -> in.skipNBytes(in.readUnsignedShort());
                case 9 -> {
                    int t=in.readUnsignedByte(),n=count(1);
                    if(n>MAX_NODES||(t==0&&n!=0))throw new IOException("NBT list size");
                    for(int i=0;i<n;i++)skip(t,depth+1);
                }
                case 10 -> {while(true){int t=in.readUnsignedByte();if(t==0)break;in.skipNBytes(in.readUnsignedShort());skip(t,depth+1);}}
                case 11 -> in.skipNBytes((long)count(4)*4);case 12 -> in.skipNBytes((long)count(8)*8);
                default -> throw new IOException("Invalid NBT type "+type);
            }
        }
    }
}
