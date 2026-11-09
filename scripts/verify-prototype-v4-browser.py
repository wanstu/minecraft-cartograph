#!/usr/bin/env python3
"""Smoke test the served Cartograph HTML in dadm's installed Chrome."""
import asyncio,json,subprocess,tempfile,shutil
from urllib.request import urlopen
import websockets

async def main():
    tmp=tempfile.mkdtemp(prefix="cartograph-proto-check-")
    proc=subprocess.Popen(["google-chrome","--headless=new","--no-sandbox","--disable-gpu",
      "--disable-dev-shm-usage","--no-first-run","--remote-allow-origins=*",
      "--remote-debugging-port=9347",f"--user-data-dir={tmp}",
      "http://127.0.0.1:8765/cartograph_大地图_交互高保真.html"],
      stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
      pages=[]
      for _ in range(70):
        await asyncio.sleep(.18)
        try:
          pages=json.load(urlopen("http://127.0.0.1:9347/json",timeout=1))
          if any("cartograph_" in p.get("url","") for p in pages):break
        except Exception:pass
      page=next(p for p in pages if "cartograph_" in p.get("url",""))
      async with websockets.connect(page["webSocketDebuggerUrl"],max_size=8_000_000) as ws:
        seq=0
        async def evaluate(js):
          nonlocal seq
          seq+=1
          await ws.send(json.dumps({"id":seq,"method":"Runtime.evaluate","params":{"expression":js,"returnByValue":True}}))
          while True:
            reply=json.loads(await ws.recv())
            if reply.get("id")==seq:
              assert "exceptionDetails" not in reply.get("result",{}),reply
              return reply["result"]["result"].get("value")
        await asyncio.sleep(1)
        initial=await evaluate('({canvas:document.querySelector("#map").width,player:!!document.querySelector("#playerArrow")})')
        print("INITIAL",initial)
        assert initial["canvas"]>0 and initial["player"]
        state=await evaluate('''(()=>{
          document.querySelector('[data-act="settings"]').click();
          document.querySelector('[data-settings-tab="cost"]').click();
          return {rates:[...document.querySelectorAll('[data-rate]')].map(x=>[x.dataset.rate,x.min,x.value]),
                  flags:[...document.querySelectorAll('[data-permanent]')].map(x=>[x.dataset.permanent,x.checked])}
        })()''')
        print("COST SETTINGS",state)
        assert len(state["rates"])==6 and len(state["flags"])==3
        assert all(x[1]=="0.1" for x in state["rates"])
        test=await evaluate('''(()=>{
          const slider=document.querySelector('[data-rate="inkCost"]');
          slider.value=0;slider.dispatchEvent(new Event('input',{bubbles:true}));
          const rate=JSON.parse(localStorage.getItem('cartograph-costs-v4'));
          const checkbox=document.querySelector('[data-permanent="permanentInk"]');
          checkbox.checked=true;checkbox.dispatchEvent(new Event('change',{bubbles:true}));
          return {rate:rate.inkCost,permanent:JSON.parse(localStorage.getItem('cartograph-costs-v4')).permanentInk}
        })()''')
        print("BOUNDARIES",test)
        assert test["rate"]>=.1 and test["permanent"]
        resource=await evaluate('''(()=>{
          document.querySelector('[data-act="modal-close"]').click();
          document.querySelector('[data-act="atlas"]').click();
          document.querySelector('[data-act="materials"]').click();
          return {ink:document.querySelector('[data-material="ink"]').disabled,
                 glow:document.querySelector('[data-material="glow"]').disabled,
                 dye:document.querySelector('[data-material="dye-red"]').disabled}
        })()''')
        print("MATERIAL SLOTS",resource)
        assert resource["ink"] and not resource["glow"] and resource["dye"]
        print("PASS live HTML V4 browser smoke")
    finally:
      proc.terminate()
      try:proc.wait(timeout=5)
      except subprocess.TimeoutExpired:proc.kill()
      shutil.rmtree(tmp,ignore_errors=True)

if __name__=="__main__":
    asyncio.run(main())
