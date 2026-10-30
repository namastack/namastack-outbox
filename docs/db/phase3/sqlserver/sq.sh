#!/bin/bash
# usage: sq.sh [sqlcmd args...]  (reads SQL from stdin or -Q)
exec docker exec -i outbox-db-P3-mssql /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P 'P3_Strong#Pass1' -d "${DB:-outbox}" -b -W -I -s "|" "$@"
