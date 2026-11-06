#!/usr/bin/env bash
# Creates a one-form query overlay directory for `--queries <dir>`.
# usage: bin/form-overlay.sh <QM-file.sql> <outdir>     (the file's `-- id:` header gives the query id; the form is copied to <outdir>/<id>.sql)
# prints the query id on stdout. The directory is emptied first, so it holds exactly this one form.
set -euo pipefail
f="${1:?QM file}"; out="${2:?outdir}"
id="$(sed -n 's/^--[[:space:]]*id:[[:space:]]*\([A-Za-z0-9_]*\).*/\1/p' "$f" | head -1)"
[ -n "$id" ] || { echo "no '-- id:' header in $f" >&2; exit 1; }
mkdir -p "$out"; rm -f "$out"/*.sql; cp "$f" "$out/$id.sql"
echo "$id"
