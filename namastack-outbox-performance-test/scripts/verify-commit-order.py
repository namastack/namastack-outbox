#!/usr/bin/env python3
"""Resolve backward wall-clock timestamps using independent WAL commit positions; fail closed."""
import json
import subprocess
import sys
from pathlib import Path

container, run_id, report_path = sys.argv[1:]
report = Path(report_path)

def sql(text):
    result = subprocess.run(['docker','exec',container,'psql','-X','-qAt','-v','ON_ERROR_STOP=1',
        '-U','username','-d','outbox_performance_test','-c',text],text=True,capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()

def literal(value):
    return "'" + value.replace("'", "''") + "'"

def lsn_number(value):
    high, low = value.split('/')
    return (int(high,16)<<32) + int(low,16)

def lsn_text(value):
    return f'{value>>32:X}/{value&0xffffffff:X}'

raw_path=report/'completion-order.json'
original=json.loads(raw_path.read_text())
if original.get('valid'):
    raise SystemExit(0)
if original.get('missingCompletions') or original.get('checkedRecords') != original.get('expectedRecords'):
    raise SystemExit('Incomplete delivery cannot be repaired by WAL verification')
raw_sql=f"""
WITH ordered AS (
 SELECT id,record_key,completed_at,xmin::text AS current_xid,
 lag(completed_at) OVER w AS previous_completed_at,lag(xmin::text) OVER w AS previous_xid
 FROM outbox_record WHERE starts_with(id,{literal(run_id+'-record-')})
 WINDOW w AS (PARTITION BY record_key ORDER BY created_at,id)
)
SELECT COALESCE(json_agg(o),'[]'::json) FROM ordered o WHERE completed_at < previous_completed_at
"""
pairs=json.loads(sql(raw_sql))
if len(pairs) != original['violations'] or not pairs:
    raise SystemExit('Timestamp violation set changed; no safe WAL revalidation')
sql('CREATE EXTENSION IF NOT EXISTS pg_walinspect')
files=json.loads(sql("SELECT json_agg(name) FROM pg_ls_waldir() WHERE name ~ '^[0-9A-F]{24}$'"))
segment_size=int(sql("SELECT pg_size_bytes(current_setting('wal_segment_size'))"))
end=lsn_number(sql('SELECT pg_current_wal_lsn()'))
starts=sorted((int(f[8:16],16)<<32)+int(f[16:24],16)*segment_size for f in files)
start=min(x for x in starts if x<end)
targets={int(p[k]) for p in pairs for k in ['current_xid','previous_xid']}
if max(targets)-min(targets)>2**31:
    raise SystemExit('XID wraparound requires manual inspection')

# Locate an approximate WAL range, then widen until every required commit is found.
# XIDs are not strict commit order; this search is only a locator, never ordering evidence.
def first_xid(at):
    stop=min(at+1048576,end)
    value=sql(f"SELECT xid::text FROM pg_get_wal_records_info('{lsn_text(at)}','{lsn_text(stop)}') WHERE xid::text <> '0' LIMIT 1")
    return int(value) if value else None
lo,hi=start,end
while hi-lo>segment_size:
    mid=((lo+hi)//(2*segment_size))*segment_size
    if mid<=lo:break
    xid=first_xid(mid)
    if xid is None:break
    if xid<min(targets):lo=mid
    else:hi=mid
center=lo
commits={}
for radius in [4,16,64,256,1024,4096]:
    a=max(start,center-radius*segment_size)
    b=min(end,hi+radius*segment_size)
    ids=','.join(str(x) for x in sorted(targets))
    query=f"""SELECT COALESCE(json_agg(c),'[]'::json) FROM (
      SELECT start_lsn::text AS lsn,xid::text AS xid,description
      FROM pg_get_wal_records_info('{lsn_text(a)}','{lsn_text(b)}')
      WHERE resource_manager='Transaction' AND record_type='COMMIT' AND xid::text::bigint IN ({ids})
    ) c"""
    commits={int(c['xid']):c for c in json.loads(sql(query))}
    if targets.issubset(commits):break
    if a==start and b==end:break
missing=sorted(targets-commits.keys())
violations=[]
ambiguous=[]
for p in pairs:
    current=commits.get(int(p['current_xid']));previous=commits.get(int(p['previous_xid']))
    if current and previous:
        p.update(currentCommitLSN=current['lsn'],previousCommitLSN=previous['lsn'],
                 currentCommit=current['description'],previousCommit=previous['description'])
        if lsn_number(current['lsn'])<lsn_number(previous['lsn']):violations.append(p)
        if current['lsn']==previous['lsn']:ambiguous.append(p)
evidence=dict(timestampViolations=len(pairs),actualCommitOrderViolations=len(violations),
              ambiguousSameCommitPairs=len(ambiguous),missingCommitXids=missing,records=pairs)
(report/'completion-order-wal.json').write_text(json.dumps(evidence,indent=2)+'\n')
if missing or violations or ambiguous:
    raise SystemExit('WAL evidence does not establish strict completion commit order')
(report/'completion-order-timestamps.json').write_text(json.dumps(original,indent=2)+'\n')
verified=original.copy()
verified.update(valid=True,violations=0,timestampViolations=len(pairs),walVerifiedPairs=len(pairs),
    verificationMethod='Timestamp order; backward-timestamp pairs independently verified by strictly increasing WAL commit LSN')
raw_path.write_text(json.dumps(verified,indent=2)+'\n')
print(json.dumps(verified))
