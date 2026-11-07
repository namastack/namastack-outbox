#!/usr/bin/env python3
"""opt2 summarizer: opt2-summarize.py reads|wc|s10|s9|plans|equiv. mdb-main reference = mean of phase-6 pass1/pass2 (results/final)."""
import sys, csv, os, re, glob
D = '/home/azamojski/namastack-outbox/docs/db-mariadb'; R = D + '/results/opt2'; F = D + '/results/final'
GRP = {}
for f in glob.glob(D + '/harness/catalog/mdb-final/mariadb/*.sql'):
    q = os.path.basename(f)[:-4]
    for l in open(f):
        if l.startswith('-- group:'): GRP[q] = l.split(':')[1].strip(); break
ORDER = ['keys_strict','keys_nonstrict','pages','insert','upd_complete','upd_retry','upd_fail','delete_by_id','count_statuses','count_partitions','oldest_created','oldest_due_strict','oldest_due_nonstrict','count_new','count_failed','count_completed','count_partition_single']
LABS = ['S8-S2','S8-S4','S8-S5','S16-S2','S16-S4','S4','S5','S7','S9']
def rd(path):
    m = {}
    if not os.path.exists(path): return m
    for r in csv.DictReader(open(path)):
        v = r['median_ms']; n = r.get('note', '') or ''
        if v: m[r['query_id']] = (float(v), n)
        elif 'stopped at limit' in n:
            mm = re.search(r'limit (\d+) s', n); m[r['query_id']] = (None, 'over ' + (mm.group(1) if mm else '?') + ' s')
        else: m[r['query_id']] = (None, n)
    return m
def val(x): return x[0] if x and x[0] is not None else None
def mean(a): a = [x for x in a if x is not None]; return sum(a)/len(a) if a else None
def thr(q): return {'HOT': (0.11, None), 'MON': (0.10, 0.04), 'WRITE': (0.60, 0.2)}.get(GRP.get(q, 'HOT'), (0.11, None))
def fmt(x):
    if x is None: return '-'
    if isinstance(x, tuple):
        v, n = x
        if v is None: return n or '-'
        return (f'{v:.3f}' if v < 10 else f'{v:.1f}') + ('*' if 'slow' in n else '')
    return f'{x:.3f}' if x < 10 else f'{x:.1f}'
def change(q, a, b):
    if a is None or b is None: return 'n/a'
    rel, ab = thr(q); d = a - b; r = d / b if b else 0
    ok = abs(r) <= rel if (ab is None or max(a, b) >= 0.3) else abs(d) <= ab
    if rel == 0.6 and abs(d) < ab: ok = True
    return f'{r*100:+.0f}%' + (' (nd)' if ok else '')
def mainref(lab, q):
    xs = []
    for p in ('pass1', 'pass2'):
        m = rd(f'{F}/{p}/mdb-main/mariadb/{lab}/timings.csv')
        if q == 'count_statuses' and q not in m and 'count_new' in m and 'count_failed' in m:
            a, b = val(m['count_new']), val(m['count_failed']); xs.append(a + b if a is not None and b is not None else None)
        else: xs.append(val(m.get(q)))
    return mean(xs)
def table(lab):
    o = rd(f'{R}/mdb-opt2/mariadb/{lab}/timings.csv'); f = rd(f'{R}/mdb-final/mariadb/{lab}/timings.csv')
    if not o and not f: return
    print(f'\n### {lab}\n\n| query | opt2 | final | final vs opt2 | main (ph6 mean) | opt2 note | final note |\n|---|---|---|---|---|---|---|')
    for q in ORDER:
        if q not in o and q not in f: continue
        a, b = val(o.get(q)), val(f.get(q))
        print(f'| {q} | {fmt(o.get(q))} | {fmt(f.get(q))} | {change(q, b, a)} | {fmt(mainref(lab, q))} | {(o.get(q) or ("",""))[1][:40]} | {(f.get(q) or ("",""))[1][:40]} |')
def reads():
    for lab in LABS: table(lab)
def load(p):
    m = {}
    if os.path.exists(p):
        for r in csv.reader(open(p)):
            if len(r) >= 2: m[r[0]] = r[1]
    return m
def wc():
    phases = ['insert_1thread','insert_4threads','upd_complete_committed','upd_retry_committed','upd_fail_committed','delete_by_id_committed']
    f = lambda m, k: float(m[k]) if k in m else float('nan')
    for lab in ('S2-wc', 'S4-wc'):
        print(f'\n### {lab}: per phase redo B/write / pages/write / p50 ms / p95 ms / throughput per s\n')
        print('| run | ' + ' | '.join(phases) + ' |\n|---|' + '---|' * len(phases))
        ms = {}
        for v, base in (('mdb-opt2', R), ('mdb-final', R), ('mdb-main (ph6 p1)', F + '/pass1')):
            vv = v.split()[0]; m = load(f'{base}/{vv}/mariadb/{lab}/writecost.csv'); ms[v] = m
            if m: print(f'| {v} | ' + ' | '.join(f"{f(m,ph+'.os_log_written.per_write'):.0f} / {f(m,ph+'.pages_written.per_write'):.2f} / {f(m,ph+'.p50_ms'):.2f} / {f(m,ph+'.p95_ms'):.2f} / {f(m,ph+'.throughput_per_s'):.0f}" for ph in phases) + ' |')
        print('\nfinal vs opt2 (redo 12 %, p50 8 %, p95 25 % thresholds):\n')
        def pc(a, b, th): d = (a-b)/b*100 if b else float('nan'); return f'{d:+.0f}%' + (' (nd)' if abs(d) <= th*100 else '')
        for ph in phases:
            g = lambda v, k: f(ms[v], ph + k)
            print(f"- {ph}: redo {pc(g('mdb-final','.os_log_written.per_write'), g('mdb-opt2','.os_log_written.per_write'), .12)}; p50 {pc(g('mdb-final','.p50_ms'), g('mdb-opt2','.p50_ms'), .08)}; p95 {pc(g('mdb-final','.p95_ms'), g('mdb-opt2','.p95_ms'), .25)}; pages/write {pc(g('mdb-final','.pages_written.per_write'), g('mdb-opt2','.pages_written.per_write'), .64)}")
        print('\nIndex sizes MB before -> after, count, all-phase redo:\n')
        for v, m in ms.items():
            if not m: continue
            ib = {k[len('index_bytes_before.'):]: float(m[k]) for k in m if k.startswith('index_bytes_before.')}
            ia = {k[len('index_bytes_after.'):]: float(m[k]) for k in m if k.startswith('index_bytes_after.')}
            print(f'- {v}: ' + ', '.join(f'{k.replace("idx_outbox_record_","")} {ib[k]/1e6:.0f}->{ia.get(k,0)/1e6:.0f}' for k in ib) + f", total {f(m,'indexes_total_bytes_before')/1e6:.0f}->{f(m,'indexes_total_bytes_after')/1e6:.0f} MB, count {f(m,'index_count'):.0f}, all-phase redo {f(m,'all_phases.os_log_written.total')/1e6:.0f} MB")
def s10():
    print('\n| label | run | processed/s | inserted/s | keys p50 | pages p50 | upd p50 | redo MB | HLL end |\n|---|---|---|---|---|---|---|---|---|')
    g = lambda t, pat: (re.search(pat, t).group(1) if re.search(pat, t) else '?')
    for lab in ['S10-S2-p1-unthr','S10-S2-p4-unthr','S10-S4-p4']:
        for v, base in (('mdb-opt2', R), ('mdb-final', R), ('mdb-main', F + '/pass1'), ('mdb-main p2', F + '/pass2')):
            fs = glob.glob(f'{base}/{v.split()[0]}/mariadb/{lab}/concurrency-*.txt')
            if not fs: continue
            t = open(fs[0]).read(); ol = g(t, r'os_log_written=(\d+)')
            print(f"| {lab} | {v} | {g(t, r'processed_per_s: ([\d.]+)')} | {g(t, r'inserted_per_s: ([\d.]+)')} | {g(t, r'keys_query_ms: n=\d+ p50=([\d.]+)')} | {g(t, r'pages_query_ms: n=\d+ p50=([\d.]+)')} | {g(t, r'update_ms: n=\d+ p50=([\d.]+)')} | {int(ol)/1e6:.0f} | {g(t, r'end (\d+)')} |")
def s9():
    print('\n| run | removed_by_retention | capped at s | churn s | table_rows | data MB | index MB | file MB | HLL after | redo MB |\n|---|---|---|---|---|---|---|---|---|---|')
    for v, base in (('mdb-opt2', R), ('mdb-final', R), ('mdb-main (ph6)', F + '/pass1'), ('mdb-opt (ph6)', F + '/pass1')):
        p = f"{base}/{v.split()[0]}/mariadb/S9/churn.txt"
        if not os.path.exists(p): continue
        t = open(p).read()
        rows = re.findall(r'# table_rows,data_length,index_length\n(\d+)\t(\d+)\t(\d+)', t); fs = re.findall(r'# tablespace file_size,allocated_size\n(\d+)', t)
        g = lambda pt: (re.search(pt, t).group(1) if re.search(pt, t) else '?')
        print(f"| {v} | {g(r'removed_by_retention: (\d+)')} | {g(r'retention_capped_at_s: (\S+)')} | {g(r'churn_seconds: (\d+)')} | {rows[0][0]}->{rows[-1][0]} | {int(rows[0][1])/1e6:.0f}->{int(rows[-1][1])/1e6:.0f} | {int(rows[0][2])/1e6:.0f}->{int(rows[-1][2])/1e6:.0f} | {int(fs[0])/1e6:.0f}->{int(fs[-1])/1e6:.0f} | {g(r'history_list_length_after: (\d+)')} | {int(g(r'os_log_written=(\d+)'))/1e6:.0f} |")
def plans():
    for lab in LABS:
        o = rd(f'{R}/mdb-opt2/mariadb/{lab}/timings.csv'); f = rd(f'{R}/mdb-final/mariadb/{lab}/timings.csv')
        for q in ORDER:
            a, b = val(o.get(q)), val(f.get(q))
            if a is None and b is None: continue
            diff = a is not None and b is not None and 'nd' not in change(q, b, a)
            if (a or 0) > 5 or (b or 0) > 5 or diff:
                print(f'\n#### {lab} / {q}: opt2 {fmt(o.get(q))} ms, final {fmt(f.get(q))} ms')
                for v in ('mdb-opt2', 'mdb-final'):
                    p = f'{R}/{v}/mariadb/{lab}/plans/{q}.summary.txt'
                    if os.path.exists(p): print(f'\n`{v}`:\n```\n' + ''.join(l[:400] + '\n' if len(l) > 400 else l for l in open(p)) + '```')
def equiv():
    for f in sorted(glob.glob(R + '/equiv/mariadb/*/*.md')):
        t = open(f).read(); c = {k: len(re.findall(rf'\| {k} \|', t)) for k in ('PASS', 'WARN', 'FAIL', 'N/A', 'SKIP', 'ERROR')}
        print(f'- `{os.path.relpath(f, R)}`: ' + ', '.join(f'{k} {n}' for k, n in c.items() if n))
        for l in t.splitlines():
            if re.search(r'\| (WARN|FAIL|ERROR) \|', l): print('  - ' + l[:300])
if __name__ == '__main__': globals()[sys.argv[1]]()
