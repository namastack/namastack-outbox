#!/bin/bash
# usage: sq.sh  (SQL from stdin) ; sqlplus as app user, pipe-free output
exec docker exec -i outbox-db-P3-oracle sqlplus -S -L outbox/outbox@//localhost/XEPDB1
