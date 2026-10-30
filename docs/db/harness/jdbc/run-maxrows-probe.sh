#!/usr/bin/env bash
# Usage: run-maxrows-probe.sh <container> [maxRows] [partitions] [filter|nofilter]  -- needs java 17+ and pgjdbc in the Gradle cache
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$(find ~/.gradle/caches/modules-2/files-2.1/org.postgresql/postgresql -name 'postgresql-42.*.jar' ! -name '*sources*' | sort -V | tail -1)"
HOST="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$1")"
echo "pgjdbc: $JAR, host: $HOST"
java -cp "$JAR" "$DIR/MaxRowsProbe.java" "$HOST" "${2:-10}" "${3:-256}" "${4:-nofilter}"
