#!/usr/bin/env python3
"""Plans (pass 1 summaries) for queries > 5 ms (c-1 or c-2) and for regressions; usage plans.py"""
import csv, os, re, sys
R='/home/azamojski/namastack-outbox/docs/db-mariadb/results/c/pass1'
LABS=['S8-S2','S8-S4','S8-S5','S8-S6','S8-S7','S16-S2','S16-S4','S1','S2','S4','S4-b100','S5','S6','S7']
def med(v,lab,q):
    try:
        for r in csv.DictReader(open(f'{R}/{v}/mariadb/{lab}/timings.csv')):
            if r['query_id']==q: return float(r['median_ms']) if r['median_ms'] else None
    except Exception: return None
def plan(v,lab,q):
    p=f'{R}/{v}/mariadb/{lab}/plans/{q}.summary.txt'
    if not os.path.exists(p): return '-'
    out=[]
    for l in open(p):
        m=re.match(r'select#(\d+) table=(\S+) access=(\S+) key=(\S+) key_parts=(\[[^\]]*\]).*?examined=(\S+)',l)
        if m: out.append(f"{m.group(2)}:{m.group(3)}:{(m.group(4) or '').replace('idx_outbox_record_','')}{m.group(5)} ex={m.group(6)}")
        if l.startswith('filesort'): 
            if 'filesort: yes' in l: out.append('filesort')
    return ' ; '.join(out) if out else open(p).read().splitlines()[0][:100]
HOT=['keys_strict','keys_nonstrict','pages','count_statuses','count_partitions','oldest_created','oldest_due_strict','oldest_due_nonstrict']
for lab in LABS:
    rows=[]
    for q in HOT:
        ms={v:med(v,lab,q) for v in ('c-ref','c-1','c-2')}
        if any((x or 0)>5 for x in (ms['c-1'],ms['c-2'])) or (ms['c-ref'] is None and any(ms[v] for v in ('c-1','c-2'))==False):
            rows.append((q,ms))
    if not rows: continue
    print(f'\n#### {lab}')
    for q,ms in rows:
        print(f'- `{q}` ms c-ref/c-1/c-2: '+' / '.join('over limit' if ms[v] is None else f'{ms[v]:.1f}' for v in ('c-ref','c-1','c-2')))
        for v in ('c-ref','c-1','c-2'): print(f'  - {v}: {plan(v,lab,q)}')
