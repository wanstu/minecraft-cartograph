#!/usr/bin/env python3
"""Summarize JFR scalar events with bounded memory and cross-checked phase timing."""
import argparse, bisect, collections, datetime, json, pathlib, re, subprocess

CARTOGRAPH='com.wanstu.cartographv3.'
GAME_THREADS=('main','Render thread','Server thread')
SAMPLE_TYPES=('jdk.ExecutionSample','jdk.NativeMethodSample')

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--lab',type=pathlib.Path,required=True)
    parser.add_argument('--java',type=pathlib.Path,required=True)
    args=parser.parse_args()
    lab=args.lab.resolve()
    report=json.loads((lab/'instance/performance-results.json').read_text())
    if not report.get('complete'):
        raise SystemExit('Measurement has not completed')
    groups={phase['phase']:{
        'threads':{},'gc_pauses_ms':[],'slow_io':[],'monitor_blocks':[],'parks':[],
        'sampled_allocation_bytes':collections.Counter(),'sampled_allocations_by_thread':{},
    } for phase in report['phases']}
    helper=pathlib.Path(__file__).with_name('JfrPhaseEvents.java')
    command=[str(args.java),'-Xmx256m',str(helper),str(lab/'trace.jfr'),'0','9999999999999']

    # Cross-check JFR's TSC timestamps against this JVM's unified GC wall-clock log.
    # On this older dadm CPU, the JFR clock runs at a different rate from wall time.
    starts={}
    pauses=[]
    for line in (lab/'gc.log').read_text().splitlines():
        match=re.search(r'^\[([^]]+)\].*\[gc,start\s*\] GC\((\d+)\) Pause Young',line)
        if match:
            starts[int(match[2])]=datetime.datetime.fromisoformat(match[1]).timestamp()*1000
        match=re.search(r'^\[([^]]+)\].*\[gc\s*\] GC\((\d+)\) Pause.* ([\d.]+)ms$',line)
        if match:
            end=datetime.datetime.fromisoformat(match[1]).timestamp()*1000
            duration=float(match[3])
            pauses.append((end-duration,duration))
    calibration=subprocess.run(command+['gc'],capture_output=True,text=True,check=True)
    anchors=sorted((event['time'],starts[event['gc_id']])
                   for event in map(json.loads,calibration.stdout.splitlines())
                   if event['gc_id'] in starts)
    if len(anchors)<2:
        raise SystemExit('Need at least two matching young-GC clock anchors')
    times=[x for x,y in anchors]
    scale=(anchors[-1][1]-anchors[0][1])/(anchors[-1][0]-anchors[0][0])
    def clock(time):
        index=max(0,min(len(anchors)-2,bisect.bisect_right(times,time)-1))
        x0,y0=anchors[index]
        x1,y1=anchors[index+1]
        return y0+(time-x0)*(y1-y0)/(x1-x0)
    def phase_at(time):
        return next((phase['phase'] for phase in report['phases']
                     if phase['start_epoch_ms']<=time<=phase['end_epoch_ms']),None)
    for time,duration in pauses:
        phase=phase_at(time)
        if phase:
            groups[phase]['gc_pauses_ms'].append(duration)
    print(f'JFR clock anchors={len(anchors)}, wall/JFR scale={scale:.9f}; GC pauses from gc.log')
    with subprocess.Popen(command,stdout=subprocess.PIPE,text=True) as proc:
        for line in proc.stdout:
            event=json.loads(line)
            phase=phase_at(clock(event['time']))
            if phase is None:
                continue
            group=groups[phase]
            methods=event['methods']
            cart=next((method for method in methods if method.startswith(CARTOGRAPH)),None)
            name=event['thread']
            kind=event['type']
            duration=event['duration_ms']*scale
            if kind in SAMPLE_TYPES:
                data=group['threads'].setdefault(name,{
                    'samples':0,'java_samples':0,'native_samples':0,
                    'cartograph_samples':0,'measurement_samples':0,
                    'top_methods':collections.Counter(),
                    'top_cartograph_methods':collections.Counter(),
                    'inclusive_cartograph_methods':collections.Counter(),
                })
                data['samples']+=1
                data['java_samples' if kind==SAMPLE_TYPES[0] else 'native_samples']+=1
                if methods:
                    data['top_methods'][methods[0]]+=1
                if cart:
                    data['cartograph_samples']+=1
                    data['top_cartograph_methods'][cart]+=1
                for method in set(method for method in methods if method.startswith(CARTOGRAPH)):
                    data['inclusive_cartograph_methods'][method]+=1
                if any(method.startswith('com.wanstu.cartographqa.') for method in methods):
                    data['measurement_samples']+=1
            elif kind in ('jdk.FileRead','jdk.FileWrite') and name in GAME_THREADS:
                group['slow_io'].append({
                    'event':kind,'duration_ms':duration,'path':event['path'],
                    'thread':name,'cartograph':cart,'top':methods[:5]})
            elif kind=='jdk.JavaMonitorEnter' and name in GAME_THREADS:
                group['monitor_blocks'].append({
                    'duration_ms':duration,'thread':name,'cartograph':cart,'top':methods[:5]})
            elif kind=='jdk.ThreadPark' and name in GAME_THREADS and cart:
                group['parks'].append({
                    'duration_ms':duration,'thread':name,'cartograph':cart,'top':methods[:8]})
            elif kind=='jdk.ObjectAllocationSample':
                if cart:
                    group['sampled_allocation_bytes'][cart]+=event['weight']
                if name in GAME_THREADS:
                    allocations=group['sampled_allocations_by_thread'].setdefault(name,collections.Counter())
                    allocations[cart or (methods[0] if methods else '?')]+=event['weight']
        if proc.wait()!=0:
            raise SystemExit('JFR stream failed')
    summary={}
    for name,group in groups.items():
        for data in group['threads'].values():
            for key in ('top_methods','top_cartograph_methods','inclusive_cartograph_methods'):
                data[key]=data[key].most_common(15)
            data['cartograph_sample_percent']=100*data['cartograph_samples']/data['samples']
        pauses=group['gc_pauses_ms']
        summary[name]={
            'threads':group['threads'],'gc_pause_count':len(pauses),
            'gc_pause_total_ms':sum(pauses),'gc_pause_max_ms':max(pauses,default=0),
            'slow_io':group['slow_io'],'monitor_blocks':group['monitor_blocks'],
            'cartograph_parks':group['parks'],
            'top_sampled_cartograph_allocations':group['sampled_allocation_bytes'].most_common(15),
            'top_sampled_allocations_by_thread':{
                thread:counts.most_common(15)
                for thread,counts in group['sampled_allocations_by_thread'].items()},
        }
    summary['_clock_calibration']={
        'young_gc_anchors':len(anchors),'wall_per_jfr_time':scale,
        'method':'Piecewise interpolation of matching young-GC IDs; GC durations from unified gc.log',
        'first_anchor':anchors[0],'last_anchor':anchors[-1],
        'note':'Sampling percentages describe observed stack samples, not exact CPU-time shares.'}
    (lab/'jfr-summary.json').write_text(json.dumps(summary,indent=2))
    lines=['| Phase | Frame p95 / p99 ms | Client / server p95 ms | GC pause max ms |',
           '|---|---:|---:|---:|']
    for phase in report['phases']:
        frame=phase['frame']
        name=phase['phase']
        lines.append(f"| {name} | {frame['p95_ms']:.2f} / {frame['p99_ms']:.2f} "
                     f"| {phase['client_tick']['p95_ms']:.2f} / {phase['server_tick']['p95_ms']:.2f} "
                     f"| {summary[name]['gc_pause_max_ms']:.2f} |")
    (lab/'summary.md').write_text('\n'.join(lines)+'\n')
    print('\n'.join(lines))
    print('Wrote jfr-summary.json; allocation weights are sampling estimates, not exact totals.')

if __name__=='__main__':
    main()
