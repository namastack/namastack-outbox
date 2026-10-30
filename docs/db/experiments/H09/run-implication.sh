#!/usr/bin/env bash
# usage: run-implication.sh <container> -> prints plan node lines for each statement of implication.sql
docker exec -i "$1" psql -U postgres -X -q < "$(dirname "$0")/implication.sql" 2>&1 | grep -E '^==|Scan|Filter|Index Cond|ERROR'
