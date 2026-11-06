#!/usr/bin/env bash
# Lane environment for MySQL runs. usage: source bin/lane.sh 1   (prints the settings; fails if the CPUs are not available)
#
# Host (2026-11-06): bare metal, Intel Core i7-8700K (6 cores / 12 threads, one shared L3 of 12 MiB, 1 NUMA node), 15 GiB RAM,
#   ~22 GB free disk. CPU n and n+6 are SMT siblings (core n).
# The host has a single L3 and little memory/disk, so there is ONE measuring lane. Lane 1: the DB container gets 2 full cores
# (cores 1,2 = CPUs 1,7,2,8) with --cpus=4, the client JVM core 3 (CPUs 3,9). Cores 0, 4, 5 (CPUs 0,6,4,10,5,11) are left for
# the OS, the orchestrator, non-measuring sub-agents, Gradle and git. Gradle builds of worktrees must not run while a timed step runs.
# If the CPU set changes, update the table below and record it in STATE.md.
_lane="${1:?lane 1}"
case "$_lane" in
  1) export HARNESS_LANE=1 HARNESS_CPUSET="1,7,2,8" HARNESS_CLIENT_CPUSET="3,9" ;;
  *) echo "lane $_lane: this host has one lane only (see header)"; return 1 2>/dev/null || exit 1 ;;
esac
_eff="$(cat /sys/fs/cgroup/cpuset.cpus.effective 2>/dev/null || true)"
if [ -n "$_eff" ]; then
  _avail=" $(python3 -c "import sys
s=set()
for p in sys.argv[1].split(','):
    a,_,b=p.partition('-'); s.update(range(int(a),int(b or a)+1))
print(' '.join(map(str,sorted(s))))" "$_eff") "
  for _c in ${HARNESS_CPUSET//,/ } ${HARNESS_CLIENT_CPUSET//,/ }; do
    case "$_avail" in *" $_c "*) ;; *) echo "lane $_lane: CPU $_c not in cgroup cpuset $_eff - update bin/lane.sh"; return 1 2>/dev/null || exit 1 ;; esac
  done
fi
echo "lane $HARNESS_LANE: db cpuset $HARNESS_CPUSET, client cpuset $HARNESS_CLIENT_CPUSET, port mysql $((13306 + 100 * HARNESS_LANE))"
