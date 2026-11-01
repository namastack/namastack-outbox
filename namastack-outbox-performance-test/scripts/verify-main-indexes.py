#!/usr/bin/env python3
"""Verify the isolated database uses exactly the indexes in the checked-out library schema."""
import json
import re
import subprocess
import sys
from pathlib import Path

container, schema_path = sys.argv[1:]
schema = Path(schema_path).read_text()
expected = {}
for name, table, definition in re.findall(r'CREATE INDEX IF NOT EXISTS (\w+)\s+ON (\w+)\s*(\(.*?);', schema, re.S):
    expected[name] = (table, definition)
for table, column in [('outbox_record', 'id'), ('outbox_instance', 'instance_id'), ('outbox_partition', 'partition_number')]:
    expected[table + '_pkey'] = (table, '(' + column + ')')
query = """SELECT json_agg(i ORDER BY indexname) FROM (
SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public'
AND tablename IN ('outbox_record','outbox_instance','outbox_partition')) i"""
actual = json.loads(subprocess.check_output(['docker', 'exec', container, 'psql', '-X', '-qAt', '-U', 'username', '-d', 'outbox_performance_test', '-c', query], text=True))
errors = []
if {row['indexname'] for row in actual} != set(expected):
    errors.append('Index name set differs from shipped schema')
for row in actual:
    spec = expected.get(row['indexname'])
    normalize = lambda value: re.sub(r'[\s()\"]', '', value).lower()
    actual_definition = row['indexdef'].split('USING btree ', 1)[-1]
    if spec and (row['tablename'] != spec[0] or normalize(actual_definition) != normalize(spec[1])):
        errors.append('Index definition differs: ' + row['indexname'])
print(json.dumps({'valid': not errors, 'schema': schema_path, 'indexes': actual, 'errors': errors}, indent=2))
if errors:
    raise SystemExit('Library index verification failed: ' + '; '.join(errors))
