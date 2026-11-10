#!/usr/bin/env python3
'''Check real Minecraft page-cache files before/after one world edit.

Only explicitly changed 16x16 chunk pixels may differ. Maps are not redrawn
wholesale; survey and colored masks + material balances must be byte-identical.
Usage: python3 scripts/qa_compare_page_snapshots.py before.gz after.gz -1 -1
'''
import gzip,struct,sys
from pathlib import Path

def load(path):
    data=gzip.decompress(Path(path).read_bytes())
    ptr=0
    def read(fmt):
        nonlocal ptr
        size=struct.calcsize('>'+fmt)
        result=struct.unpack_from('>'+fmt,data,ptr)
        ptr+=size
        return result[0] if len(result)==1 else result
    def utf():
        nonlocal ptr
        n=read('H')
        out=data[ptr:ptr+n].decode('utf8')
        ptr+=n
        return out
    def array(n):
        nonlocal ptr
        result=data[ptr:ptr+n]
        ptr+=n
        if len(result)!=n:raise ValueError('truncated')
        return result
    assert read('I')==0x43545035
    atlas,dim=utf(),utf()
    px,pz=read('ii')
    pixels=array(128*128);features=array(128*128)
    n=read('i')
    heights=array(n*2);depth=array(n)
    survey,colored=read('QQ')
    ink,pigment,glow=read('iii')
    compass,inrange=read('??')
    scanned,generated,painted=read('iii')
    anchor=utf()
    ax,az,totalPages,side,surveyTotal,colorTotal=read('iiiiii')
    return dict(atlas=atlas,dim=dim,px=px,pz=pz,pixels=pixels,
                features=features,heights=heights,depth=depth,
                survey=survey,colored=colored,ink=ink,pigment=pigment,
                glow=glow,compass=compass,inrange=inrange,scanned=scanned,
                generated=generated,painted=painted,anchor=anchor,ax=ax,
                az=az,totalPages=totalPages,side=side,
                surveyTotal=surveyTotal,colorTotal=colorTotal)

if __name__=='__main__':
    if len(sys.argv)!=5:raise SystemExit('before.gz after.gz chunkX chunkZ')
    old,new=load(sys.argv[1]),load(sys.argv[2])
    cx,cz=int(sys.argv[3]),int(sys.argv[4])
    assert (old['px'],old['pz'])==(new['px'],new['pz'])==(
        cx//8,cz//8),'wrong page for chunk'
    for key in ('atlas','dim','survey','colored','ink','pigment','glow','compass','inrange',
                'scanned','generated','painted','anchor','ax','az','totalPages','side',
                'surveyTotal','colorTotal'):
        assert old[key]==new[key],f'metadata changed unexpectedly: {key}'
    x0=(cx%8)*16;z0=(cz%8)*16
    changes={field:0 for field in ('pixels','features','heights','depth')}
    outside=0
    for field,step in [('pixels',1),('features',1),('heights',2),('depth',1)]:
        a,b=old[field],new[field]
        assert len(a)==len(b)
        if len(a)==0:continue
        for z in range(128):
            for x in range(128):
                offset=(z*128+x)*step
                if a[offset:offset+step]!=b[offset:offset+step]:
                    changes[field]+=1
                    if not (x0<=x<x0+16 and z0<=z<z0+16):
                        outside+=1
    assert outside==0,f'out-of-chunk changes: {outside}'
    assert sum(changes.values())>0,'no terrain change observed'
    print(f'PASS world edit patch: {changes}, changed_outside_chunk=0, '
          f'paper/ink/pigment/masks unchanged, page=({old["px"]},{old["pz"]})')
