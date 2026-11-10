#!/usr/bin/env python3
'''Reproducible, real Minecraft X11 visual QA on dadm.

Requires python3-xlib, pillow and an independent X server (Xephyr :92).
Never reads or edits the developer's personal Minecraft save. Screenshots
are of the isolated test instance and are not synthetic HTML mockups.

  DISPLAY=:92 python3 scripts/qa_dadm_visual.py shot build/visual-qa/main.png
  DISPLAY=:92 python3 scripts/qa_dadm_visual.py key m
  DISPLAY=:92 python3 scripts/qa_dadm_visual.py click 640 350
  DISPLAY=:92 python3 scripts/qa_dadm_visual.py type "give Dev cartograph:atlas"
  DISPLAY=:92 python3 scripts/qa_dadm_visual.py diff BEFORE.png AFTER.png --box 200,100,400,300

Coordinate system defaults to root X11 display pixels, not GUI scale.
Use --client to click pixels relative to the Minecraft render window.
'''
import argparse
import hashlib
import os
import sys
import time
from pathlib import Path

KEY_NAMES = {':':'colon', '/':'slash', '@':'at', '_':'underscore',
             '.':'period', '-':'minus', '=':'equal', ',':'comma',
             '[':'bracketleft', ']':'bracketright', ' ':'space'}

def connect():
    from Xlib import X,display
    from Xlib.ext import xtest
    d=display.Display(os.environ.get('DISPLAY', ':92'))
    window=next((w for w in d.screen().root.query_tree().children
                 if 'Minecraft' in str(w.get_wm_name() or '')),None)
    if window is None: raise RuntimeError('Minecraft test client window missing')
    window.set_input_focus(X.RevertToParent,X.CurrentTime)
    d.sync()
    return d,window,X,xtest

def emit_key(d, X, xtest, name, seconds=.07):
    from Xlib import XK
    sym=XK.string_to_keysym(KEY_NAMES.get(name,name))
    if not sym: raise ValueError('Unsupported key: '+repr(name))
    code=d.keysym_to_keycode(sym)
    if not code: raise ValueError('No keycode for: '+repr(name))
    shift=sym!=d.keycode_to_keysym(code,0)
    shift_code=d.keysym_to_keycode(XK.XK_Shift_L)
    if shift: xtest.fake_input(d,X.KeyPress,detail=shift_code)
    xtest.fake_input(d,X.KeyPress,detail=code)
    d.sync();time.sleep(seconds)
    xtest.fake_input(d,X.KeyRelease,detail=code)
    if shift: xtest.fake_input(d,X.KeyRelease,detail=shift_code)
    d.sync();time.sleep(.06)

def click(d,w,X,xtest,x,y,client=False):
    if client:
        geom=w.get_geometry()
        x+=geom.x; y+=geom.y
    xtest.fake_input(d,X.MotionNotify,x=int(x),y=int(y))
    d.sync();time.sleep(.15)
    xtest.fake_input(d,X.ButtonPress,detail=1)
    d.sync();time.sleep(.15)
    xtest.fake_input(d,X.ButtonRelease,detail=1)
    d.sync()

def screenshot(path):
    from PIL import ImageGrab
    image=ImageGrab.grab(xdisplay=os.environ.get('DISPLAY',':92'))
    dest=Path(path)
    dest.parent.mkdir(parents=True,exist_ok=True)
    image.save(dest)
    digest=hashlib.sha256(dest.read_bytes()).hexdigest()
    print(f'SCREENSHOT={dest.resolve()} SIZE={image.width}x{image.height} SHA256={digest}')

def difference(first,second,box=None):
    from PIL import Image, ImageChops, ImageStat
    a=Image.open(first).convert('RGB')
    b=Image.open(second).convert('RGB')
    if a.size!=b.size: raise ValueError('Different display sizes')
    if box:
        x,y,w,h=map(int,box.split(','))
        a=a.crop((x,y,x+w,y+h));b=b.crop((x,y,x+w,y+h))
    diff=ImageChops.difference(a,b)
    mean=sum(ImageStat.Stat(diff).mean)/3
    mask=diff.convert('L').point(lambda c: 255 if c>=20 else 0)
    from collections import Counter
    changed=sum(1 for c in mask.getdata() if c)
    total=a.width*a.height
    print(f'IMAGE_DIFF mean_abs_rgb={mean:.3f} changed_px_20={changed}/{total} ratio={changed/total:.4%}')

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--client',action='store_true',help='click is relative to Minecraft window')
    ap.add_argument('--wait',type=float,default=.0)
    ap.add_argument('action',choices=['shot','key','type','click','hold','wheel','diff'])
    ap.add_argument('args',nargs='*')
    ap.add_argument('--box')
    v=ap.parse_args()
    if v.action=='shot':
        if len(v.args)!=1:ap.error('shot PATH')
        screenshot(v.args[0]);return
    if v.action=='diff':
        if len(v.args)!=2:ap.error('diff BEFORE AFTER')
        difference(*v.args,v.box);return
    d,w,X,xtest=connect()
    if v.action=='key':
        if len(v.args)!=1:ap.error('key NAME')
        emit_key(d,X,xtest,v.args[0])
    elif v.action=='type':
        if len(v.args)!=1:ap.error('type TEXT')
        for c in v.args[0]:emit_key(d,X,xtest,c,seconds=.11)
    elif v.action=='click':
        if len(v.args)!=2:ap.error('click X Y')
        click(d,w,X,xtest,*map(int,v.args),client=v.client)
    elif v.action=='wheel':
        if len(v.args)!=1:ap.error('wheel signed_steps (positive scroll up)')
        steps=int(v.args[0])
        geom=w.get_geometry()
        xtest.fake_input(d,X.MotionNotify,x=geom.x+geom.width//2,y=geom.y+geom.height//2)
        for _ in range(abs(steps)):
            detail=4 if steps>0 else 5
            xtest.fake_input(d,X.ButtonPress,detail=detail)
            xtest.fake_input(d,X.ButtonRelease,detail=detail)
            d.sync();time.sleep(.1)
    elif v.action=='hold':
        if len(v.args)!=2:ap.error('hold KEY SECONDS')
        from Xlib import XK
        sym=XK.string_to_keysym(v.args[0])
        code=d.keysym_to_keycode(sym)
        xtest.fake_input(d,X.KeyPress,detail=code)
        d.sync();time.sleep(float(v.args[1]))
        xtest.fake_input(d,X.KeyRelease,detail=code);d.sync()
    time.sleep(v.wait)

if __name__=='__main__':main()
