#!/usr/bin/env python3
"""Phase-6 summarizer -> prints SUMMARY.md body sections. usage: final-summarize.py reads|spreadcells|wc|s10|s9|sizes|plans|bp2g|equiv"""
import sys, csv, os, re, glob
D = '/home/azamojski/namastack-outbox/docs/db-mariadb'; R = D + '/results/final'
GRP = {}
for f in glob.glob(D + '/harness/catalog/mdb-final/mariadb/*.sql'):
    q = os.path.basename(f)[:-4]
    for l in open(f):
        if l.startswith('-- group:'): GRP[q] = l.split(':')[1].strip(); break
ORDER = ['keys_strict','keys_nonstrict','pages','insert','upd_complete','upd_retry','upd_fail','delete_by_id','count_statuses','count_partitions','oldest_created','oldest_due_strict','oldest_due_nonstrict','count_new','count_failed','count_completed','count_partition_single']
LABS = ['S8-S2','S8-S4','S8-S5','S8-S6','S8-S7','S16-S2','S16-S4','S1','S2','S4','S4-b100','S5','S6','S7','S9']
VS = ['mdb-main','mdb-opt','mdb-final']
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
def run(tree, v, lab): return fixmain(rd(f'{R}/{tree}/{v}/mariadb/{lab}/timings.csv'), v)
def fixmain(m, v='mdb-main'):
    if v == 'mdb-main' and val(m.get('count_statuses')) is None and 'count_new' in m and 'count_failed' in m:  # main sends count_new + count_failed
        a, b = val(m['count_new']), val(m['count_failed'])
        m['count_statuses'] = ((a + b) if a is not None and b is not None else None, 'sum count_new+count_failed')
    return m
def thr(q): return {'HOT': (0.11, None), 'MON': (0.10, 0.04), 'WRITE': (0.60, 0.2)}.get(GRP.get(q, 'HOT'), (0.11, None))
def fmt(x):
    if x is None: return '-'
    v, n = x
    if v is None: return n or '-'
    return (f'{v:.3f}' if v < 10 else f'{v:.1f}') + ('*' if 'slow' in n else '')
def val(x): return x[0] if x and x[0] is not None else None
def mean(a): a = [x for x in a if x is not None]; return sum(a)/len(a) if a else None
def change(q, a, b):
    if a is None or b is None: return 'n/a'
    rel, ab = thr(q); d = a - b; r = d / b if b else 0
    ok = abs(r) <= rel if (ab is None or max(a, b) >= 0.3) else abs(d) <= ab
    if rel == 0.6 and abs(d) < ab: ok = True
    return f'{r*100:+.0f}%' + (' (nd)' if ok else '')
def spread(q, p1, p2):
    if p1 is None or p2 is None: return False
    rel, ab = thr(q); m = min(p1, p2)
    if ab is not None and max(p1, p2) < 0.3 and rel != 0.6: return abs(p1-p2) > 2*ab
    if rel == 0.6: return abs(p1-p2) > 2*0.2 and abs(p1-p2)/m > 1.2
    return abs(p1-p2)/m > 2*rel
def rr(v, lab, q):  # rerun value if present
    return val(rd(f'{R}/rerun1/{v}/mariadb/{lab}/timings.csv').get(q))
def table(lab):
    t = {(p, v): run(p, v, lab) for p in ('pass1', 'pass2') for v in VS}
    if not any(t.values()): return None, []
    out = [f'\n### {lab}\n', '| query | main p1 | main p2 | opt p1 | opt p2 | final p1 | final p2 | final vs main | final vs opt | flags |', '|---|---|---|---|---|---|---|---|---|---|']
    flagged = []
    for q in ORDER:
        if not any(q in t[k] for k in t): continue
        row = []; mv = {}; fl = []
        for v in VS:
            a, b = t[('pass1', v)].get(q), t[('pass2', v)].get(q)
            row += [fmt(a), fmt(b)]
            pa, pb = val(a), val(b)
            mv[v] = mean([pa, pb]) if (pa is not None or pb is not None) else None
            if spread(q, pa, pb): fl.append(f'SPREAD>2x {v}'); flagged.append((v, lab, q))
        def ch(c, base, name):
            if mv[c] is None: return 'final over limit' if mv[base] is not None else 'n/a'
            if mv[base] is None: return f'{name} over limit (final {mv[c]:.2f} ms)'
            return change(q, mv[c], mv[base])
        out.append(f'| {q} | ' + ' | '.join(row) + f' | {ch("mdb-final","mdb-main","main")} | {ch("mdb-final","mdb-opt","opt")} | {"; ".join(fl)} |')
    return '\n'.join(out), flagged
def reads():
    for lab in LABS:
        s, _ = table(lab)
        if s: print(s)
def spreadcells():
    cells = {}
    for lab in LABS:
        if lab == 'S9': continue
        _, fl = table(lab)
        for v, l, q in fl: cells.setdefault((v, l), []).append(q)
    for k, qs in sorted(cells.items()): print(k, qs)
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
        for v in VS:
            for p in ('pass1', 'pass2'):
                m = load(f'{R}/{p}/{v}/mariadb/{lab}/writecost.csv')
                if not m: continue
                print(f'| {v} {p} | ' + ' | '.join(f"{f(m,ph+'.os_log_written.per_write'):.0f} / {f(m,ph+'.pages_written.per_write'):.2f} / {f(m,ph+'.p50_ms'):.2f} / {f(m,ph+'.p95_ms'):.2f} / {f(m,ph+'.throughput_per_s'):.0f}" for ph in phases) + ' |')
        print('\nChanges (mean of both passes) final vs main / final vs opt: redo B/write, p50 ms (thresholds redo 12 %, p50 8 %, p95 25 %):\n')
        for ph in phases:
            def mn(v, key):
                xs = [f(load(f'{R}/{p}/{v}/mariadb/{lab}/writecost.csv'), key) for p in ('pass1', 'pass2')]
                xs = [x for x in xs if x == x]; return sum(xs)/len(xs) if xs else float('nan')
            def pc(a, b, th): d = (a-b)/b*100 if b else float('nan'); return f'{d:+.0f}%' + (' (nd)' if abs(d) <= th*100 else '')
            fr = mn('mdb-final', ph+'.os_log_written.per_write'); fp = mn('mdb-final', ph+'.p50_ms')
            print(f"- {ph}: redo {pc(fr, mn('mdb-main', ph+'.os_log_written.per_write'), .12)} vs main, {pc(fr, mn('mdb-opt', ph+'.os_log_written.per_write'), .12)} vs opt; p50 {pc(fp, mn('mdb-main', ph+'.p50_ms'), .08)} vs main, {pc(fp, mn('mdb-opt', ph+'.p50_ms'), .08)} vs opt")
        print('\nIndex sizes MB before -> after the cell, and all-phase redo:\n')
        for v in VS:
            for p in ('pass1', 'pass2'):
                m = load(f'{R}/{p}/{v}/mariadb/{lab}/writecost.csv')
                if not m: continue
                ib = {k[len('index_bytes_before.'):]: float(m[k]) for k in m if k.startswith('index_bytes_before.')}
                ia = {k[len('index_bytes_after.'):]: float(m[k]) for k in m if k.startswith('index_bytes_after.')}
                print(f'- {v} {p}: ' + ', '.join(f'{k.replace("idx_outbox_record_","")} {ib[k]/1e6:.0f}->{ia.get(k,0)/1e6:.0f}' for k in ib) + f", index total {f(m,'indexes_total_bytes_before')/1e6:.0f}->{f(m,'indexes_total_bytes_after')/1e6:.0f} MB, index count {f(m,'index_count'):.0f}, all-phase redo {f(m,'all_phases.os_log_written.total')/1e6:.0f} MB")
def s10(tree_root=None, labs=None):
    print('\n| label | run | processed/s p1 | processed/s p2 | inserted/s p1 | inserted/s p2 | keys p50 p1/p2 | pages p50 p1/p2 | upd p50 p1/p2 | redo MB p1/p2 | HLL end p1/p2 |\n|---|---|---|---|---|---|---|---|---|---|---|')
    labs = labs or ['S10-S2-p1','S10-S2-p1-unthr','S10-S2-p4','S10-S2-p4-unthr','S10-S4-p4','S10-S4-p4-unthr']
    g = lambda t, pat: (re.search(pat, t).group(1) if re.search(pat, t) else '?')
    for lab in labs:
        for v in VS:
            vals = []
            for p in ('pass1', 'pass2'):
                fs = glob.glob(f'{R}/{p}/{v}/mariadb/{lab}/concurrency-*.txt'); vals.append(open(fs[0]).read() if fs else '')
            if not any(vals): continue
            P = lambda pat: [g(t, pat) if t else '-' for t in vals]
            kp = P(r'keys_query_ms: n=\d+ p50=([\d.]+)'); pp = P(r'pages_query_ms: n=\d+ p50=([\d.]+)'); up = P(r'update_ms: n=\d+ p50=([\d.]+)')
            rd_ = [f"{int(g(t, r'os_log_written=(\d+)'))/1e6:.0f}" if t and g(t, r'os_log_written=(\d+)') != '?' else '-' for t in vals]
            hl = P(r'end (\d+)')
            pr = P(r'processed_per_s: ([\d.]+)'); ins = P(r'inserted_per_s: ([\d.]+)')
            print(f'| {lab} | {v} | {pr[0]} | {pr[1]} | {ins[0]} | {ins[1]} | {kp[0]}/{kp[1]} | {pp[0]}/{pp[1]} | {up[0]}/{up[1]} | {rd_[0]}/{rd_[1]} | {hl[0]}/{hl[1]} |')
def s9():
    print('\n### S9 churn (pass 1)\n\n| run | removed_by_retention | retention capped at s | churn s | table_rows before->after | data MB before->after | index MB before->after | file MB before->after | HLL after | redo MB (global delta) |\n|---|---|---|---|---|---|---|---|---|---|')
    for v in VS:
        t = open(f'{R}/pass1/{v}/mariadb/S9/churn.txt').read() if os.path.exists(f'{R}/pass1/{v}/mariadb/S9/churn.txt') else ''
        if not t: continue
        rows = re.findall(r'# table_rows,data_length,index_length\n(\d+)\t(\d+)\t(\d+)', t); fs = re.findall(r'# tablespace file_size,allocated_size\n(\d+)', t)
        g = lambda p: (re.search(p, t).group(1) if re.search(p, t) else '?')
        print(f"| {v} | {g(r'removed_by_retention: (\d+)')} | {g(r'retention_capped_at_s: (\S+)')} | {g(r'churn_seconds: (\d+)')} | {rows[0][0]}->{rows[-1][0]} | {int(rows[0][1])/1e6:.0f}->{int(rows[-1][1])/1e6:.0f} | {int(rows[0][2])/1e6:.0f}->{int(rows[-1][2])/1e6:.0f} | {int(fs[0])/1e6:.0f}->{int(fs[-1])/1e6:.0f} | {g(r'history_list_length_after: (\d+)')} | {int(g(r'os_log_written=(\d+)'))/1e6:.0f} |")
    print('\nQueries on the churned table (pass 1 only, same limits):')
    t = {('pass1', v): run('pass1', v, 'S9') for v in VS}
    print('\n| query | main | opt | final | final vs main | final vs opt |\n|---|---|---|---|---|---|')
    for q in ORDER:
        if not any(q in t[k] for k in t): continue
        mv = {v: val(t[('pass1', v)].get(q)) for v in VS}
        print(f"| {q} | " + ' | '.join(fmt(t[('pass1', v)].get(q)) for v in VS) + f" | {change(q, mv['mdb-final'], mv['mdb-main'])} | {change(q, mv['mdb-final'], mv['mdb-opt'])} |")
def plans():
    seen = set()
    for lab in LABS:
        t = {(p, v): run(p, v, lab) for p in ('pass1',) for v in VS}
        fin = t[('pass1', 'mdb-final')]; mn = t[('pass1', 'mdb-main')]
        for q in ORDER:
            f = val(fin.get(q)); m = val(mn.get(q))
            if f is None: continue
            reg = m is not None and 'nd' not in change(q, f, m) and f > m
            if f > 5 or reg:
                print(f'\n#### {lab} / {q}: final {f:.2f} ms, main {fmt(mn.get(q))} ms' + (' (regression vs main)' if reg else ''))
                for v in ('mdb-final', 'mdb-main'):
                    p = f'{R}/pass1/{v}/mariadb/{lab}/plans/{q}.summary.txt'
                    if os.path.exists(p):
                        print(f'\n`{v}`:\n```\n' + ''.join(l[:400] + '\n' if len(l) > 400 else l for l in open(p)) + '```')
def bp2g():
    B = R + '/bp2g'
    print('\nbuffer pool: ' + open(B + '/buffer-pool.txt').read().strip().replace('\n', '; ') + '\n')
    for lab in ('S8-S2', 'S8-S4', 'S8-S5'):
        a = fixmain(rd(f'{B}/mdb-main/mariadb/{lab}/timings.csv')); b = rd(f'{B}/mdb-final/mariadb/{lab}/timings.csv')
        m128 = {q: mean([val(run(p, 'mdb-main', lab).get(q)) for p in ('pass1', 'pass2')]) for q in ORDER}
        f128 = {q: mean([val(run(p, 'mdb-final', lab).get(q)) for p in ('pass1', 'pass2')]) for q in ORDER}
        print(f'\n### {lab} at 2G\n\n| query | main 2G | final 2G | final vs main (2G) | main 128M | final 128M | final vs main (128M) |\n|---|---|---|---|---|---|---|')
        for q in ORDER:
            if q not in a and q not in b: continue
            fa, fb = val(a.get(q)), val(b.get(q))
            print(f'| {q} | {fmt(a.get(q))} | {fmt(b.get(q))} | {change(q, fb, fa)} | {fmt((m128[q], ""))} | {fmt((f128[q], ""))} | {change(q, f128[q], m128[q])} |')
    for lab, kind in (('S2-wc', 'wc'),):
        print('\n### S2-wc at 2G (redo B/write / pages/write / p50 / p95 / thr)\n')
        for v in ('mdb-main', 'mdb-final'):
            m = load(f'{B}/{v}/mariadb/{lab}/writecost.csv')
            f = lambda k: float(m[k]) if k in m else float('nan')
            print(f'- {v}: ' + ' | '.join(f"{ph.split('_')[0][:3]}{ph.split('_')[1][:2]} {f(ph+'.os_log_written.per_write'):.0f} / {f(ph+'.pages_written.per_write'):.2f} / {f(ph+'.p50_ms'):.2f} / {f(ph+'.p95_ms'):.2f} / {f(ph+'.throughput_per_s'):.0f}" for ph in ['insert_1thread','insert_4threads','upd_complete_committed','upd_retry_committed','upd_fail_committed','delete_by_id_committed']))
    print('\n### S10-S2-p4 at 2G\n')
    for v in ('mdb-main', 'mdb-final'):
        fs = glob.glob(f'{B}/{v}/mariadb/S10-S2-p4/concurrency-*.txt')
        if fs:
            t = open(fs[0]).read(); print(f"- {v}: " + ' '.join(re.findall(r'(?:processed_per_s|inserted_per_s): [\d.]+|keys_query_ms: n=\d+ p50=[\d.]+|pages_query_ms: n=\d+ p50=[\d.]+|update_ms: n=\d+ p50=[\d.]+', t)))
    print('\n128M reference (mean of pass 1/2 above): see S10 table, label S10-S2-p4.')
def equiv():
    for f in sorted(glob.glob(R + '/equiv/mariadb/*/*.md')):
        t = open(f).read(); c = {k: len(re.findall(rf'\| {k} \|', t)) for k in ('PASS', 'WARN', 'FAIL', 'N/A', 'SKIP', 'ERROR')}
        print(f'- `{os.path.relpath(f, R)}`: ' + ', '.join(f'{k} {n}' for k, n in c.items() if n))
        for l in t.splitlines():
            if re.search(r'\| (WARN|FAIL|ERROR) \|', l): print('  - ' + l[:300])
if __name__ == '__main__':
    globals()[sys.argv[1]]()
