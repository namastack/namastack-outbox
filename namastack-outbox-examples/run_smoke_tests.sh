set -euo pipefail

for dir in namastack-outbox-example-*; do
  [ -f "$dir/gradlew" ] || continue
  echo "==> $(basename "$dir")"
  (cd "$dir" && ./gradlew test --no-daemon --refresh-dependencies)
done
