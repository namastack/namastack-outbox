#!/usr/bin/env python3
"""M8 (plans only): ANALYZE FORMAT=JSON of the P1/P2 queries on the run schema `m8` for n owned partitions and eq_range_index_dive_limit.
usage: m8.py <outdir> <scenario> <ddlname>   (schema m8 must be prepared; lane 1 container)"""
import subprocess, sys, json, os, re, csv
out, scen, ddl = sys.argv[1:4]
CAT = '/home/azamojski/namastack-outbox/docs/db-mariadb/harness/catalog/mdb-opt/mariadb/'
NOW = "2026-01-01 12:00:00"
Q = {  # id -> sql_select_limit (batch 10 / one / none)
 'keys_strict': 10, 'keys_nonstrict': 10, 'oldest_created': None, 'oldest_due_strict': 1, 'oldest_due_nonstrict': 1, 'count_partitions': None}
def sql_of(qid, parts):
    t = [l for l in open(CAT + qid + '.sql').read().split('\n') if not l.startswith('--')]
    s = ' '.join(t).strip()
    lim = Q[qid]
    s = s.replace(':partitions', ','.join(map(str, parts))).replace(':status', "'NEW'").replace(':now', f"'{NOW}'")
    s = s.replace(':limit', str(lim if lim else 10))
    return s
def run(stmts):
    p = subprocess.run(['docker','exec','-i','outbox-bench-mariadb-L1','mariadb','-uroot','-pbench','-N','-B','-r','m8'], input=stmts, capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr
def tables(node, acc):
    if isinstance(node, dict):
        if 'table_name' in node and 'access_type' in node: acc.append(node)
        for v in node.values(): tables(v, acc)
    elif isinstance(node, list):
        for v in node: tables(v, acc)
def analyze(qid, parts, dive):
    sel = sql_of(qid, parts); lim = Q[qid]
    pre = f"SET time_zone='+00:00'; SET SESSION eq_range_index_dive_limit={dive}; "
    setst = f"SET STATEMENT {'SQL_SELECT_LIMIT=%d, ' % lim if lim else ''}max_statement_time=60 FOR "
    rc, so, se = run(pre + setst + "ANALYZE FORMAT=JSON " + sel + ";")
    kind = 'ANALYZE'
    if rc != 0 or not so.strip().startswith('{'):
        kind = 'EXPLAIN (ANALYZE stopped at 60 s)'
        rc, so, se = run(pre + (f"SET STATEMENT SQL_SELECT_LIMIT={lim} FOR " if lim else "") + "EXPLAIN FORMAT=JSON " + sel + ";")
    return kind, so, se
os.makedirs(out, exist_ok=True)
rows = []
cfgs = [(n, 200) for n in (16, 64, 199, 200, 201, 256)] + [(256, 0), (256, 300)]
for qid in Q:
    for n, dive in cfgs:
        parts = sorted({i * 256 // n for i in range(n)})
        assert len(parts) == n
        kind, so, se = analyze(qid, parts, dive)
        tag = f"{qid}-p{n}-dive{dive}"
        open(f"{out}/{tag}.json", 'w').write(so if so.strip() else se)
        try: j = json.loads(so)
        except Exception: rows.append([scen, ddl, qid, n, dive, kind, 'ERROR', se.strip()[:80], '', '', '']); continue
        acc = []; tables(j, acc)
        t0 = acc[0] if acc else {}
        rows.append([scen, ddl, qid, n, dive, kind, ' | '.join(f"{t.get('table_name')}:{t.get('key','-')}" for t in acc[:3]),
                     t0.get('rows',''), t0.get('r_rows',''), j.get('query_block',{}).get('r_total_time_ms',''),
                     t0.get('r_total_time_ms','')])
with open(f"{out}/summary.csv", 'w', newline='') as f:
    w = csv.writer(f); w.writerow(['scenario','ddl','query','owned','dive_limit','mode','tables:keys','est_rows(outer)','r_rows(outer)','r_total_time_ms(query)','r_total_time_ms(outer)']); w.writerows(rows)
