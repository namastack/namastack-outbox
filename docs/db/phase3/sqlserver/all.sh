#!/bin/bash
# usage: all.sh A|B   (seeds, runs base, F, C)
cd $(dirname $0); S=$1
./seed.sh $S >/dev/null
./sq.sh < idx-undo.sql >/dev/null
python3 -I run.py $S base > $S-base.log 2>&1
./sq.sh < idx-F.sql >/dev/null; python3 -I run.py $S F full > $S-F.log 2>&1; ./sq.sh < idx-undo.sql >/dev/null
./sq.sh < idx-C.sql >/dev/null; python3 -I run.py $S C full > $S-C.log 2>&1; ./sq.sh < idx-undo.sql >/dev/null
