#!/usr/bin/env python3
"""Round 2 summarizer: write cost, reads, S10. usage: summarize.py <wc|reads|s10|plans> [v4|v7]"""
import sys, csv, os, re, glob
R = '/home/azamojski/namastack-outbox/docs/db-mariadb/results/r2'
RUNS = ['base', 'sp', 'spc', 'base2']
def rn(d, v): return f'r2-{d}-{v}'
def wc(path):
    m = {}
    for r in csv.reader(open(path)):
        if len(r) >= 2: m[r[0]] = r[1]
    return m
def f(m, k):
    try: return float(m[k])
    except Exception: return float('nan')
def do_wc(v, lab):
    phases = ['insert_1thread', 'insert_4threads', 'upd_complete_committed', 'upd_retry_committed', 'upd_fail_committed', 'delete_by_id_committed']
    print(f'### {lab} {v}: redo B/write | pages/write | p50 ms | p95 ms | thr/s')
    for d in RUNS:
        p = f'{R}/{rn(d,v)}/mariadb/{lab}/writecost.csv'
        if not os.path.exists(p): continue
        m = wc(p); row = []
        for ph in phases:
            row.append(f"{ph.split('_')[0][:3]}{ph.split('_')[1][:2]}: {f(m,ph+'.os_log_written.per_write'):.0f} / {f(m,ph+'.pages_written.per_write'):.2f} / {f(m,ph+'.p50_ms'):.2f} / {f(m,ph+'.p95_ms'):.2f} / {f(m,ph+'.throughput_per_s'):.0f}")
        print(f'{d:6}', ' | '.join(row))
        ib = {k[len('index_bytes_before.'):]: float(m[k]) for k in m if k.startswith('index_bytes_before.')}
        ia = {k[len('index_bytes_after.'):]: float(m[k]) for k in m if k.startswith('index_bytes_after.')}
        print('       sizes MB before->after:', ', '.join(f'{k.replace("idx_outbox_record_","")}:{ib[k]/1e6:.0f}->{ia.get(k,0)/1e6:.0f}' for k in ib), f"total idx {f(m,'indexes_total_bytes_before')/1e6:.0f}->{f(m,'indexes_total_bytes_after')/1e6:.0f} MB, all redo {f(m,'all_phases.os_log_written.total')/1e6:.0f} MB")
def do_reads(v, lab):
    data = {}
    for d in RUNS:
        p = f'{R}/{rn(d,v)}/mariadb/{lab}/timings.csv'
        if os.path.exists(p):
            data[d] = {r['query_id']: r for r in csv.DictReader(open(p))}
    if not data: return
    qs = list(next(iter(data.values())).keys())
    print(f'### {lab} {v}: median ms (rows; note)')
    print('query'.ljust(24), *[d.ljust(14) for d in data])
    for q in qs:
        print(q.ljust(24), *[(f"{float(data[d][q]['median_ms']):.2f}" + ('*' if data[d][q].get('note') else '')).ljust(14) if q in data[d] else '-'.ljust(14) for d in data])
def do_s10(v):
    print(f'### S10 {v}')
    for d in RUNS:
        p = f'{R}/{rn(d,v)}/mariadb/S10-S2-p1-unthr/concurrency-p1-r0.txt'
        if os.path.exists(p):
            t = open(p).read()
            g = lambda pat: (re.search(pat, t) or [None, '?'])[1]
            print(d, 'processed/s', g(r'processed_per_s: ([\d.]+)'), 'inserted/s', g(r'inserted_per_s: ([\d.]+)'), 'keys p50', g(r'keys_query_ms: n=\d+ p50=([\d.]+)'), 'pages p50', g(r'pages_query_ms: n=\d+ p50=([\d.]+)'), 'upd p50', g(r'update_ms: n=\d+ p50=([\d.]+)'), 'ins p50', g(r'insert_ms: n=\d+ p50=([\d.]+)'), 'redo MB', int(g(r'os_log_written=(\d+)') or 0)/1e6 if g(r'os_log_written=(\d+)')!='?' else '?')
if __name__ == '__main__':
    k, v = sys.argv[1], sys.argv[2]
    if k == 'wc': do_wc(v, 'S2-wc'); do_wc(v, 'S4-wc')
    elif k == 'reads':
        for lab in ['S8-S4', 'S16-S4', 'S4', 'S8-S5', 'S5']: do_reads(v, lab)
    elif k == 's10': do_s10(v)
