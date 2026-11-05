#!/usr/bin/env bash
# Lane environment for parallel MariaDB runs. usage: source bin/lane.sh <1|2>   (prints the settings; fails if the CPUs are not available)
#
# Host (2026-11-05): LXC container on an AMD Ryzen 9 7900X (2 CCDs, each with its own L3). Usable CPUs (cgroup cpuset):
#   0,2-5,7,9-11,13-15,17-18,21,23  -> CCD0: 0,2,3,4,5,13,14,15,17   CCD1: 7,9,10,11,18,21,23   (CPU n and n+12 are SMT siblings)
# Each lane stays on one CCD so lanes do not share L3. DB containers get 2 full cores (4 threads) with --cpus=4, the client JVM
# gets 2-3 other threads of the same CCD. CPUs 5,17 (CCD0) are left for the orchestrator, Gradle and git.
# "Solo" cells (writecost, concurrency, churn, final validation) run in lane 1 while lane 2 is idle (no container running a step).
# If the cgroup cpuset changes (LXC reconfigured, reboot), update the table below and record it in STATE.md.
_lane="${1:?lane 1|2}"
case "$_lane" in
  1) export HARNESS_LANE=1 HARNESS_CPUSET="2,14,3,15" HARNESS_CLIENT_CPUSET="0,4,13" ;;
  2) export HARNESS_LANE=2 HARNESS_CPUSET="9,21,11,23" HARNESS_CLIENT_CPUSET="7,10,18" ;;
  *) echo "unknown lane $_lane"; return 1 2>/dev/null || exit 1 ;;
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
echo "lane $HARNESS_LANE: db cpuset $HARNESS_CPUSET, client cpuset $HARNESS_CLIENT_CPUSET, port $((13307 + 100 * HARNESS_LANE))"
