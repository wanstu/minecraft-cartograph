#!/usr/bin/env python3
"""Integration test: Cartograph HTML dual masks, local persistence, and no terrain rebuild."""
import asyncio
import json
import shutil
import subprocess
import tempfile
from urllib.request import urlopen

import websockets

URL = "http://127.0.0.1:8765/cartograph_大地图_交互高保真.html"
DEBUG_PORT = 9348

async def main():
    tmp = tempfile.mkdtemp(prefix="cartograph-v5-mask-check-")
    proc = subprocess.Popen(
        ["google-chrome", "--headless=new", "--no-sandbox", "--disable-gpu",
         "--disable-dev-shm-usage", "--no-first-run",
         "--remote-allow-origins=*", f"--remote-debugging-port={DEBUG_PORT}",
         f"--user-data-dir={tmp}", URL],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        page = None
        for _ in range(110):
            await asyncio.sleep(.17)
            try:
                all_pages = json.load(urlopen(f"http://127.0.0.1:{DEBUG_PORT}/json", timeout=1))
                page = next((p for p in all_pages if "cartograph_" in p.get("url", "")), None)
                if page:
                    break
            except Exception:
                pass
        assert page, "Chrome did not open Cartograph page"
        async with websockets.connect(page["webSocketDebuggerUrl"], max_size=12_000_000) as ws:
            seq = 0
            async def evaluate(expression):
                nonlocal seq
                seq += 1
                current = seq
                await ws.send(json.dumps({"id":current, "method":"Runtime.evaluate",
                     "params":{"expression":expression, "returnByValue":True}}))
                while True:
                    result = json.loads(await ws.recv())
                    if result.get("id") == current:
                        assert "exceptionDetails" not in result.get("result", {}), result
                        return result["result"]["result"].get("value")

            await asyncio.sleep(1.3)
            initial = await evaluate("(()=>{const p=window.__cartographPreviewState;return {g:p.geometryBuilds,u:p.maskUpdates,s:p.surveyed,c:p.colorized,n:p.total,b:document.querySelector('#map').width}})()")
            print("INITIAL", initial)
            assert initial["g"] == 1 and initial["u"] == 1
            assert 0 < initial["c"] < initial["s"] < initial["n"]
            assert initial["b"] > 0
            opened = await evaluate("""(()=>{
              document.querySelector('[data-act="atlas"]').click();
              document.querySelector('[data-act="materials"]').click();
              let b=document.querySelector('[data-material="ink"]'); b.click();
              const p=window.__cartographPreviewState;
              return {g:p.geometryBuilds,u:p.maskUpdates,s:p.surveyed,c:p.colorized,ink:p.ink}
            })()""")
            print("AFTER INK", opened)
            assert opened["s"] > initial["s"] and opened["c"] == initial["c"]
            assert opened["g"] == initial["g"] and opened["u"] > initial["u"]
            colored = await evaluate("""(()=>{
              for(const dye of ['red','yellow','blue']){
                const btn=document.querySelector('[data-material="dye-'+dye+'"]');
                if(!btn || btn.disabled)throw Error('dye not available: '+dye);
                btn.click();
              }
              const p=window.__cartographPreviewState;
              return {g:p.geometryBuilds,u:p.maskUpdates,s:p.surveyed,c:p.colorized,pigment:p.pigment};
            })()""")
            print("AFTER THREE DYES", colored)
            assert colored["c"] > opened["c"] and colored["s"] == opened["s"]
            assert colored["g"] == opened["g"] and colored["u"] > opened["u"]
            settings = await evaluate("""(()=>{
              document.querySelector('[data-act="modal-close"]').click();
              document.querySelector('[data-act="settings"]').click();
              document.querySelector('[data-settings-tab="cost"]').click();
              const rates=[...document.querySelectorAll('[data-rate]')];
              const perms=[...document.querySelectorAll('[data-permanent]')];
              const slider=document.querySelector('[data-rate="inkCost"]');
              slider.value='0';
              slider.dispatchEvent(new Event('input',{bubbles:true}));
              return {rates:rates.map(x=>x.min),flags:perms.map(x=>x.dataset.permanent),
                      bounded:JSON.parse(localStorage.getItem('cartograph-costs-v5')).inkCost};
            })()""")
            print("SETTINGS", settings)
            assert len(settings["rates"]) == 6 and all(x == "0.1" for x in settings["rates"])
            assert set(settings["flags"]) == {"permanentInk","permanentColor","permanentGlow"}
            assert settings["bounded"] == 0.1
            forever = await evaluate("""(()=>{
              for(const key of ['permanentInk','permanentColor']){
                const cb=document.querySelector('[data-permanent="'+key+'"]');
                cb.checked=true;cb.dispatchEvent(new Event('change',{bubbles:true}));
              }
              const p=window.__cartographPreviewState;
              return {g:p.geometryBuilds,u:p.maskUpdates,s:p.surveyed,c:p.colorized,n:p.total};
            })()""")
            print("PERMANENT REVEALS", forever)
            assert forever["s"] == forever["n"] and forever["c"] == forever["n"]
            assert forever["g"] == initial["g"] and forever["u"] > colored["u"]
            after_switch_off = await evaluate("""(()=>{
              for(const key of ['permanentInk','permanentColor']){
                const cb=document.querySelector('[data-permanent="'+key+'"]');
                cb.checked=false;cb.dispatchEvent(new Event('change',{bubbles:true}));
              }
              const p=window.__cartographPreviewState;
              return {s:p.surveyed,c:p.colorized,n:p.total}
            })()""")
            assert after_switch_off["s"] == forever["n"] and after_switch_off["c"] == forever["n"]
            await ws.send(json.dumps({"id":100001,"method":"Page.reload","params":{"ignoreCache":True}}))
            await asyncio.sleep(1.7)
            restored = await evaluate("""(()=>{
              const p=window.__cartographPreviewState;
              return {g:p.geometryBuilds,s:p.surveyed,c:p.colorized,n:p.total}
            })()""")
            print("RELOADED",restored)
            assert restored["g"] == 1
            assert restored["s"] == forever["n"] and restored["c"] == forever["n"]
            seq += 1
            await ws.send(json.dumps({"id":seq,"method":"Page.navigate","params":{
                "url":"http://127.0.0.1:8765/cartograph_小地图_HUD原型.html"}}))
            while True:
                navigated=json.loads(await ws.recv())
                if navigated.get("id")==seq:break
            await asyncio.sleep(2.0)
            mini=await evaluate("""(()=>{
              const el=document.querySelector('#miniMap');
              return {loaded:el.complete&&el.naturalWidth>0,masked:el.src.startsWith('data:image/png'),
                      config:!!document.querySelector('#miniHud')};
            })()""")
            print("MINIMAP MASK",mini)
            assert mini["loaded"] and mini["masked"] and mini["config"]
            print("PASS: two independent masks, rates, persistence, no terrain rebuild, same HUD mask")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=6)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(tmp, ignore_errors=True)

if __name__=="__main__":
    asyncio.run(main())
