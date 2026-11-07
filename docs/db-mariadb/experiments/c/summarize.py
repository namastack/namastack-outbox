#!/usr/bin/env python3
"""Confirm-stage summarizer. usage: summarize.py reads|wc|s10|s9|sizes|iso|plans
reads: per label (partial ownership first) median ms for c-ref/c-1/c-2 pass 1 / pass 2, change vs c-ref and vs mdb-main (phase 3: mean of pass 2 and rerun1 if present else pass 1), flags."""
import sys, csv, os, re, glob, statistics
D = '/home/azamojski/namastack-outbox/docs/db-mariadb'
R = D + '/results/c'; CMP = D + '/results/cmp'
GRP = {}
for f in glob.glob(D + '/harness/catalog/mdb-opt/mariadb/*.sql'):
    q = os.path.basename(f)[:-4]
    for l in open(f):
        if l.startswith('-- group:'): GRP[q] = l.split(':')[1].strip(); break
ORDER = ['keys_strict','keys_nonstrict','pages','insert','upd_complete','upd_retry','upd_fail','delete_by_id','count_statuses','count_partitions','oldest_created','oldest_due_strict','oldest_due_nonstrict','count_new','count_failed','count_completed','count_partition_single']
LABS = ['S8-S2','S8-S4','S8-S5','S8-S6','S8-S7','S16-S2','S16-S4','S1','S2','S4','S4-b100','S5','S6','S7','S9']
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
def run(tree, v, lab): return rd(f'{tree}/{v}/mariadb/{lab}/timings.csv')
def thr(q):
    g = GRP.get(q, 'HOT'); return {'HOT': (0.11, None), 'MON': (0.10, 0.04), 'WRITE': (0.60, 0.2)}.get(g, (0.11, None))
def fmt(x):
    if x is None: return '-'
    if isinstance(x, tuple):
        v, n = x
        if v is None: return n or '-'
        s = f'{v:.3f}' if v < 10 else f'{v:.1f}'
        return s + ('*' if 'very slow' in n or 'slow' in n else '')
    return str(x)
def val(x): return x[0] if x and x[0] is not None else None
def mean(a): a = [x for x in a if x is not None]; return sum(a)/len(a) if a else None
def change(q, a, b):
    """a = candidate, b = reference (ms). returns text"""
    if a is None or b is None: return 'n/a'
    rel, ab = thr(q); d = a - b; r = d / b if b else 0
    thr_ok = abs(r) <= rel if (ab is None or max(a, b) >= 0.3) else abs(d) <= ab
    if ab is not None and rel == 0.6 and abs(d) < ab: thr_ok = True
    return f'{r*100:+.0f}%' + (' (nd)' if thr_ok else '')
def spread(q, p1, p2):
    if p1 is None or p2 is None: return False
    rel, ab = thr(q); m = min(p1, p2)
    if ab is not None and max(p1, p2) < 0.3 and rel != 0.6: return abs(p1-p2) > 2*ab
    if rel == 0.6: return abs(p1-p2) > 2*0.2 and abs(p1-p2)/m > 1.2
    return abs(p1-p2)/m > 2*rel
def main_val(lab, q, var='mdb-main'):
    if q == 'count_statuses' and var == 'mdb-main':
        a, b = main_val(lab, 'count_new', var), main_val(lab, 'count_failed', var)
        return (a + b) if (a is not None and b is not None) else None
    p1 = val(run(CMP+'/pass1', var, lab).get(q)); p2 = val(run(CMP+'/pass2', var, lab).get(q))
    rr = val(run(CMP+'/rerun1', var, lab).get(q))
    if lab == 'S9': return p1
    return mean([rr if rr is not None else p1, p2])
def reads(labs=None):
    for lab in (labs or LABS):
        t = {}
        for p in ('pass1', 'pass2'):
            for v in ('c-ref', 'c-1', 'c-2'): t[(p, v)] = run(f'{R}/{p}', v, lab)
        if not any(t.values()): continue
        print(f'\n### {lab}\n')
        print('| query | c-ref p1 | c-ref p2 | c-1 p1 | c-1 p2 | c-2 p1 | c-2 p2 | main (ph3) | opt (ph3) | c-1 vs c-ref | c-2 vs c-ref | c-1 vs main | c-2 vs main | c-1 vs main (scaled) | c-2 vs main (scaled) | flags |')
        print('|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|')
        for q in ORDER:
            if not any(q in t[k] for k in t): continue
            row = []; mv = {}; fl = []
            for v in ('c-ref', 'c-1', 'c-2'):
                a, b = t[('pass1', v)].get(q), t[('pass2', v)].get(q)
                row += [fmt(a), fmt(b)]
                mv[v] = mean([val(a), val(b)]) if (val(a) is not None or val(b) is not None) else None
                if spread(q, val(a), val(b)): fl.append(f'SPREAD>2x {v}')
            mn = main_val(lab, q)
            def ch(v, base, isref=False):
                if mv[v] is None: return 'cand over limit' if base is not None else 'n/a'
                if base is None: return ('ref over limit (cand %.2f ms)' % mv[v]) if isref else 'n/a'
                return change(q, mv[v], base)
            on = main_val(lab, q, 'mdb-opt')
            # scaled main = main x (c-ref / opt3): removes the host-speed difference between phase 3 and now (c-ref has the branch SQL/DDL of mdb-opt)
            sm = (mn * mv['c-ref'] / on) if (mn and on and mv['c-ref']) else None
            print(f'| {q} | ' + ' | '.join(row) + f' | {fmt((mn, ""))} | {fmt((on, ""))} | {ch("c-1", mv["c-ref"], True)} | {ch("c-2", mv["c-ref"], True)} | {ch("c-1", mn)} | {ch("c-2", mn)} | {ch("c-1", sm)} | {ch("c-2", sm)} | {"; ".join(fl)} |')
def wc(lab):
    phases = ['insert_1thread','insert_4threads','upd_complete_committed','upd_retry_committed','upd_fail_committed','delete_by_id_committed']
    def load(p):
        m = {}
        if os.path.exists(p):
            for r in csv.reader(open(p)):
                if len(r) >= 2: m[r[0]] = r[1]
        return m
    f = lambda m, k: float(m[k]) if k in m else float('nan')
    print(f'\n### {lab}: redo B/write | pages/write | p50 ms | p95 ms | thr/s\n')
    for src, tree in (('main', CMP + '/pass1/mdb-main'), ('main p2', CMP + '/pass2/mdb-main'), ('c-ref p1', R + '/pass1/c-ref'), ('c-ref p2', R + '/pass2/c-ref'), ('c-1 p1', R + '/pass1/c-1'), ('c-1 p2', R + '/pass2/c-1'), ('c-2 p1', R + '/pass1/c-2'), ('c-2 p2', R + '/pass2/c-2')):
        m = load(f'{tree}/mariadb/{lab}/writecost.csv')
        if not m: continue
        print(f'- **{src}**: ' + ' | '.join(f"{ph.split('_')[0][:3]}{ph.split('_')[1][:2]} {f(m,ph+'.os_log_written.per_write'):.0f} / {f(m,ph+'.pages_written.per_write'):.2f} / {f(m,ph+'.p50_ms'):.2f} / {f(m,ph+'.p95_ms'):.2f} / {f(m,ph+'.throughput_per_s'):.0f}" for ph in phases))
        ib = {k[len('index_bytes_before.'):]: float(m[k]) for k in m if k.startswith('index_bytes_before.')}
        ia = {k[len('index_bytes_after.'):]: float(m[k]) for k in m if k.startswith('index_bytes_after.')}
        print('  - sizes MB before->after: ' + ', '.join(f'{k.replace("idx_outbox_record_","")} {ib[k]/1e6:.0f}->{ia.get(k,0)/1e6:.0f}' for k in ib) + f", index total {f(m,'indexes_total_bytes_before')/1e6:.0f}->{f(m,'indexes_total_bytes_after')/1e6:.0f} MB, index count {f(m,'index_count'):.0f}, all-phase redo {f(m,'all_phases.os_log_written.total')/1e6:.0f} MB")
def s10():
    print('\n| label | run | processed/s p1 | processed/s p2 | inserted/s p1 | inserted/s p2 | keys p50 p1/p2 | pages p50 p1/p2 | upd p50 p1/p2 | redo MB p1/p2 |\n|---|---|---|---|---|---|---|---|---|---|')
    labs = ['S10-S2-p1','S10-S2-p1-unthr','S10-S2-p4','S10-S2-p4-unthr','S10-S4-p4','S10-S4-p4-unthr']
    def g(t, pat): m = re.search(pat, t); return m.group(1) if m else '?'
    for lab in labs:
        for v, tree in (('main', CMP + '/pass1/mdb-main'), ('c-ref', R + '/pass1/c-ref'), ('c-1', R + '/pass1/c-1'), ('c-2', R + '/pass1/c-2')):
            vals = []
            for pt in (tree, tree.replace('pass1', 'pass2')):
                fs = glob.glob(f'{pt}/mariadb/{lab}/concurrency-*.txt')
                vals.append(open(fs[0]).read() if fs else '')
            if not any(vals): continue
            P = lambda pat: [g(t, pat) if t else '-' for t in vals]
            kp = P(r'keys_query_ms: n=\d+ p50=([\d.]+)'); pp = P(r'pages_query_ms: n=\d+ p50=([\d.]+)'); up = P(r'update_ms: n=\d+ p50=([\d.]+)')
            rd_ = [f"{int(g(t, r'os_log_written=(\d+)'))/1e6:.0f}" if t and g(t, r'os_log_written=(\d+)') != '?' else '-' for t in vals]
            pr = P(r'processed_per_s: ([\d.]+)'); ins = P(r'inserted_per_s: ([\d.]+)')
            print(f'| {lab} | {v} | {pr[0]} | {pr[1]} | {ins[0]} | {ins[1]} | {kp[0]}/{kp[1]} | {pp[0]}/{pp[1]} | {up[0]}/{up[1]} | {rd_[0]}/{rd_[1]} |')
if __name__ == '__main__':
    k = sys.argv[1]
    if k == 'reads': reads(sys.argv[2:] or None)
    elif k == 'wc': wc('S2-wc'); wc('S4-wc')
    elif k == 's10': s10()
