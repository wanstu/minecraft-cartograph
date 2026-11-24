#!/usr/bin/env python3
"""Run one owned, isolated modpack client; never changes the source instance."""
import argparse, hashlib, json, os, pathlib, shutil, signal, subprocess, time, uuid

OWNER = 'cartograph-performance-measurement-20261123'

def prepare(args):
    lab = args.lab.resolve()
    lab.mkdir(parents=True, exist_ok=True)
    marker = lab / 'owner.json'
    if marker.exists():
        if json.loads(marker.read_text())['owner'] != OWNER:
            raise RuntimeError('Unexpected lab owner')
        print(json.dumps({'prepared': True, 'reused': True, 'instance': str(lab/'instance')}), flush=True)
        return
    instance = lab / 'instance'
    if instance.exists():
        raise RuntimeError('Refusing to overwrite an unowned instance')
    source = args.source / 'candidate/instance'
    instance.mkdir()
    for name in ('mods','config','defaultconfigs','resourcepacks','kubejs','scripts','paxi','datapacks'):
        directory = source/name
        if directory.is_dir(): shutil.copytree(directory, instance/name)
    for name in ('options.txt','servers.dat'):
        if (source/name).is_file(): shutil.copy2(source/name, instance/name)
    world = instance/'saves/BenchWorld'
    world.parent.mkdir()
    shutil.copytree(source/'saves'/args.world, world)
    (world/'session.lock').unlink(missing_ok=True)
    for directory in source.iterdir():
        if directory.is_dir() and directory.name.startswith('cartograph_'):
            shutil.copytree(directory, instance/directory.name)
    for path in (instance/'kubejs').rglob('*request*.json'):
        try:
            value=json.loads(path.read_text())
            if isinstance(value,dict) and 'run' in value:
                value['run']=False;path.write_text(json.dumps(value))
        except (OSError,ValueError): pass
    for path in (instance/'kubejs').glob('*owned*.json'):
        path.rename(path.with_suffix(path.suffix+'.disabled'))
    options=instance/'options.txt'
    values=dict(line.split(':',1) for line in options.read_text().splitlines() if ':' in line)
    values.update(renderDistance='5',simulationDistance='4',maxFps='60',enableVsync='false',
                  graphicsMode='0',particles='2',fullscreen='false',pauseOnLostFocus='false',
                  soundCategory_master='0.0',soundCategory_music='0.0')
    options.write_text('\n'.join(k+':'+v for k,v in values.items())+'\n')
    marker.write_text(json.dumps({'owner':OWNER,'source':str(args.source),'world':args.world,'created':time.time()},indent=2))
    print(json.dumps({'prepared':True,'instance':str(instance),'world':args.world}),flush=True)

def run(args):
    lab=args.lab.resolve()
    if json.loads((lab/'owner.json').read_text())['owner'] != OWNER: raise RuntimeError('Invalid owner')
    instance=lab/'instance'
    old_state=lab/'process.json'
    if old_state.exists():
        old=json.loads(old_state.read_text())
        if old.get('state')=='running' and pathlib.Path('/proc/'+str(old['pid'])).exists():
            raise RuntimeError('An owned measurement client is already running')
    for path in (instance/'mods').glob('*Cartograph*jar'): path.unlink()
    shutil.copy2(args.cartograph_jar, instance/'mods'/args.cartograph_jar.name)
    shutil.copy2(args.probe_jar, instance/'mods'/args.probe_jar.name)
    result=instance/'performance-results.json'
    result.unlink(missing_ok=True)
    raw=json.loads((args.source/'linux-java-args.json').read_text())
    rewritten=[];i=0
    replacement={'--gameDir':str(instance),'--username':'CartographPerf','--uuid':uuid.uuid5(uuid.NAMESPACE_DNS,OWNER).hex,
                 '--accessToken':'0','--userType':'legacy','--width':'1280','--height':'720'}
    skip={'--quickPlaySingleplayer','--quickPlayMultiplayer','--quickPlayRealms','--quickPlayPath','--server','--port'}
    while i<len(raw):
        value=raw[i]
        if value in replacement: rewritten.extend((value,replacement[value]));i+=2;continue
        if value in skip:i+=2;continue
        if value in ('--fullscreen','--demo'):i+=1;continue
        if value.startswith('-Xmx'):value='-Xmx2G'
        if value.startswith('-Xms'):value='-Xms512m'
        rewritten.append(value);i+=1
    rewritten[0:0]=['-XX:StartFlightRecording=name=cartograph,settings=profile,dumponexit=true,filename='+str(lab/'trace.jfr')+',maxsize=128m',
                    '-Xlog:gc*:file='+str(lab/'gc.log')+':time,uptime,level,tags']
    if args.quick: rewritten.insert(0, '-Dcartograph.measurementQuick=true')
    rewritten.extend(('--quickPlaySingleplayer','BenchWorld'))
    display=args.display or ':135'
    env=os.environ.copy();env.update(DISPLAY=display,ALSOFT_DRIVERS='null')
    stdout=(lab/'client.log').open('w');xlog=None;xvfb=None
    if args.display:
        env.pop('LIBGL_ALWAYS_SOFTWARE', None);env.pop('LP_NUM_THREADS', None)
    else:
        if pathlib.Path('/tmp/.X135-lock').exists():raise RuntimeError('Private display is occupied')
        env.update(LIBGL_ALWAYS_SOFTWARE='1',LP_NUM_THREADS='2')
        xlog=(lab/'xvfb.log').open('w')
        xvfb=subprocess.Popen([str(args.xvfb),display,'-screen','0','1280x720x24','-nolisten','tcp','-noreset','-ac'],stdout=xlog,stderr=subprocess.STDOUT)
    client=None
    def stop(*_):
        if client and client.poll() is None:os.killpg(client.pid,signal.SIGTERM)
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    started=time.time();last_print=0;reported=0
    try:
        client=subprocess.Popen([str(args.java),*rewritten],cwd=instance,env=env,stdout=stdout,stderr=subprocess.STDOUT,start_new_session=True)
        state={'owner':OWNER,'state':'running','pid':client.pid,'instance':str(instance),'display':display,
               'source_jar_sha256':hashlib.sha256(args.cartograph_jar.read_bytes()).hexdigest(),'started':started}
        old_state.write_text(json.dumps(state,indent=2));print(json.dumps(state),flush=True)
        while client.poll() is None:
            now=time.time()
            if now-started>720:stop();raise RuntimeError('Measurement client exceeded total runtime limit')
            if result.exists():
                try:
                    values=json.loads(result.read_text())
                    phases=values.get('phases',[])
                    while reported<len(phases):
                        phase=phases[reported];reported+=1
                        print(json.dumps({'finished_phase':phase['phase'],'frame':phase['frame'],'server_tick':phase['server_tick'],
                                          'disk_reads':phase['disk_reads'],'loaded_reads':phase['loaded_reads'],'gc_ms':phase['gc_ms']}),flush=True)
                except (OSError,ValueError):pass
            if now-last_print>30:
                print(json.dumps({'running_seconds':round(now-started),'phases_complete':reported}),flush=True);last_print=now
            time.sleep(1)
        code=client.wait()
        state.update(state='exited',exit_code=code,ended=time.time());old_state.write_text(json.dumps(state,indent=2))
        if code or not result.exists() or not json.loads(result.read_text()).get('complete'):
            raise RuntimeError('Measurement did not complete; inspect client.log')
        print(json.dumps({'complete':True,'exit_code':code,'result':str(result),'jfr':str(lab/'trace.jfr')}),flush=True)
    finally:
        if client and client.poll() is None:
            stop()
            try:client.wait(timeout=30)
            except subprocess.TimeoutExpired:os.killpg(client.pid,signal.SIGKILL);client.wait()
        # A bundled helper may outlive the JVM during an interrupted startup.
        if client:
            try: os.killpg(client.pid, signal.SIGTERM)
            except ProcessLookupError: pass
            deadline = time.monotonic() + 2
            while time.monotonic() < deadline:
                try: os.killpg(client.pid, 0)
                except ProcessLookupError: break
                time.sleep(.1)
            else:
                try: os.killpg(client.pid, signal.SIGKILL)
                except ProcessLookupError: pass
        if xvfb:
            xvfb.terminate()
            try:xvfb.wait(timeout=10)
            except subprocess.TimeoutExpired:xvfb.kill();xvfb.wait()
        stdout.close()
        if xlog:xlog.close()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=('prepare','run'))
    parser.add_argument('--lab',type=pathlib.Path,required=True);parser.add_argument('--source',type=pathlib.Path,required=True)
    parser.add_argument('--quick',action='store_true');parser.add_argument('--world',default='UnificationSmoke');parser.add_argument('--java',type=pathlib.Path)
    parser.add_argument('--display',help='Use an existing display without starting or stopping its server')
    parser.add_argument('--xvfb',type=pathlib.Path);parser.add_argument('--cartograph-jar',type=pathlib.Path);parser.add_argument('--probe-jar',type=pathlib.Path)
    options=parser.parse_args();prepare(options) if options.action=='prepare' else run(options)
