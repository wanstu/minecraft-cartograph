package com.wanstu.cartograph.v4;
import java.util.*;
import static com.wanstu.cartograph.v4.RegionPageStore.Key;
import static com.wanstu.cartograph.v4.MapTileCoverage.*;
public final class MapTileCoverageTest{
    static void check(boolean b,String msg){if(!b)throw new AssertionError(msg);}
    public static void main(String[] args){
        Key target=new Key(2,-3,-1),parent=parent(target),grand=parent(parent);
        Map<Key,State> data=new HashMap<>();
        Lookup lookup=k->data.getOrDefault(k,State.MISSING);
        data.put(grand,State.READY);
        var p=resolve(target,lookup).getFirst();
        check(p.source().equals(grand)&&p.u()==.25&&p.v()==.75&&p.uvSize()==.25,"negative ancestor UV");
        data.put(parent,State.READY);
        p=resolve(target,lookup).getFirst();
        check(p.source().equals(parent)&&p.u()==.5&&p.v()==.5,"nearest parent");
        Key child=new Key(1,-6,-2);data.put(child,State.READY);
        var patches=resolve(target,lookup);
        check(patches.size()==4&&patches.getFirst().source().equals(child),"child reuse on zoom out");
        check(patches.get(1).u()==.75&&patches.get(1).v()==.5&&patches.get(1).uvSize()==.25,"clipped parent quadrant");
        data.put(new Key(1,-5,-2),State.EMPTY);
        check(resolve(target,lookup).size()==3,"known empty child suppresses fallback");
        data.put(target,State.EMPTY);check(resolve(target,lookup).isEmpty(),"known empty exact suppresses all fallback");
        data.put(target,State.READY);check(resolve(target,lookup).size()==1&&resolve(target,lookup).getFirst().source().equals(target),"exact replaces fallback");
        var visible=List.of(target,new Key(2,-2,-1),new Key(2,-1,-1));
        var wanted=requests(visible,12);
        check(wanted.size()==12&&wanted.containsAll(visible)&&wanted.getFirst().equals(parent)&&new HashSet<>(wanted).size()==12,"bounded parent and neighbor plan");
        check(requests(visible,3).equals(visible),"exact coverage at budget");
        // Randomized world-space UV mapping, including negative pages and every LOD difference.
        Random random=new Random(20261127);
        for(int i=0;i<10000;i++){
            int lod=random.nextInt(10),coarseLod=lod+1+random.nextInt(10-lod);
            Key k=new Key(lod,random.nextInt(-10000,10001),random.nextInt(-10000,10001));
            int factor=1<<(coarseLod-lod);
            Key ancestor=new Key(coarseLod,Math.floorDiv(k.x(),factor),Math.floorDiv(k.z(),factor));
            var patch=resolve(k,key->key.equals(ancestor)?State.READY:State.MISSING).getFirst();
            check(patch.source().equals(ancestor),"randomized ancestor");
            check(ancestor.x()*factor+patch.u()*factor==k.x()&&ancestor.z()*factor+patch.v()*factor==k.z(),"world-space origin");
            check(patch.uvSize()*factor==1&&patch.u()>=0&&patch.v()>=0&&patch.u()+patch.uvSize()<=1&&patch.v()+patch.uvSize()<=1,"bounded UV");
        }
        // Every combination of missing/empty/ready immediate child cells with/without
        // an ancestor. No overlaps and no fallback over authoritative empty children.
        for(int ancestorMode=0;ancestorMode<3;ancestorMode++)for(int mask=0;mask<81;mask++){
            Map<Key,State> cells=new HashMap<>();
            Key k=new Key(5,-3,4),a=parent(k);
            cells.put(a,State.values()[ancestorMode]);
            int flags=mask;
            boolean[] expected=new boolean[4];
            for(int j=0;j<4;j++){
                State state=State.values()[flags%3];flags/=3;
                cells.put(new Key(4,k.x()*2+j%2,k.z()*2+j/2),state);
                expected[j]=state==State.READY||(state==State.MISSING&&ancestorMode==State.READY.ordinal());
            }
            List<Patch> result=resolve(k,key->cells.getOrDefault(key,State.MISSING));
            for(int j=0;j<4;j++){
                double x=(j%2+.5)/2,z=(j/2+.5)/2;
                int count=0;
                for(Patch part:result)if(x>=part.x()&&x<part.x()+part.size()&&z>=part.z()&&z<part.z()+part.size())count++;
                check(count==(expected[j]?1:0),"nonoverlapping quadrant coverage");
            }
        }
        // A five-level zoom jump must preserve previously rendered fine pages,
        // including negative coordinates and explicit empty descendants.
        Key big=new Key(5,-2,-1),fine=new Key(0,-63,-31),empty=new Key(0,-62,-31),ancestor=parent(big);
        Map<Key,State> deep=new HashMap<>();deep.put(fine,State.READY);deep.put(empty,State.EMPTY);
        var sparse=branches(deep.keySet());
        var isolated=resolve(big,k->deep.getOrDefault(k,State.MISSING),sparse);
        check(isolated.size()==1&&isolated.getFirst().source().equals(fine)&&isolated.getFirst().x()==1.0/32&&isolated.getFirst().z()==1.0/32&&isolated.getFirst().size()==1.0/32,"multi-level child survives zoom jump");
        deep.put(ancestor,State.READY);
        var mixed=resolve(big,k->deep.getOrDefault(k,State.MISSING),branches(deep.keySet()));
        for(int z=0;z<32;z++)for(int x=0;x<32;x++){
            double xx=(x+.5)/32,zz=(z+.5)/32;int hits=0;
            for(var part:mixed){
                if(xx>=part.x()&&xx<part.x()+part.size()&&zz>=part.z()&&zz<part.z()+part.size()){
                    hits++;
                    check(part.source().equals(x==1&&z==1?fine:ancestor),"finest actual source");
                    double wx=(big.x()+part.x())*32*(1<<big.lod());
                    double wz=(big.z()+part.z())*32*(1<<big.lod());
                    check(wx==(part.source().x()+part.u())*32*(1<<part.source().lod())&&wz==(part.source().z()+part.v())*32*(1<<part.source().lod()),"deep UV alignment");
                }
            }
            check(hits==(x==2&&z==1?0:1),"deep nonoverlap / known empty");
        }
        int[] lookups={0};
        Key huge=new Key(10,0,0),leaf=new Key(0,101,101);
        var hugeResult=resolve(huge,k->{lookups[0]++;return k.equals(leaf)?State.READY:State.MISSING;},branches(List.of(leaf)));
        check(hugeResult.size()==1&&lookups[0]<=41,"sparse traversal not exponential");
        System.out.println("PASS: multi-LOD sparse preview, 1024 nonoverlapping cell samples, explicit empty and bounded traversal");
        System.out.println("PASS: negative UV, nearest ancestor, child reuse, clipped nonoverlapping fallback, known-empty exclusion, bounded prefetch, 10000 randomized world-space UV checks, 243 mixed-child cases");
    }
}
