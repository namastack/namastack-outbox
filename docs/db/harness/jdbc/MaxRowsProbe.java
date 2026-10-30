import java.sql.*;
import java.util.*;

/**
 * Probe: is Statement.setMaxRows (JdbcClient.withMaxRows) visible to the Postgres planner?
 * Runs the key query and the cleanup id query through pgjdbc with auto_explain (ANALYZE) and
 * prints the server-side plans that were actually executed, sent back as NOTICEs.
 *
 * Run: java -cp postgresql-42.7.x.jar MaxRowsProbe.java <host> [maxRows] [partitions] [filter|nofilter]
 * (see harness/jdbc/run-maxrows-probe.sh)
 */
public class MaxRowsProbe {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://" + args[0] + ":5432/postgres";
        int maxRows = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        int nParts = args.length > 2 ? Integer.parseInt(args[2]) : 256;
        boolean filter = args.length > 3 && args[3].equals("filter");
        try (Connection c = DriverManager.getConnection(url, "postgres", "pw")) {
            try (Statement s = c.createStatement()) {
                s.execute("LOAD 'auto_explain'");
                s.execute("SET auto_explain.log_min_duration = 0");
                s.execute("SET auto_explain.log_analyze = on");
                s.execute("SET auto_explain.log_buffers = on");
                s.execute("SET auto_explain.log_timing = off");
                s.execute("SET auto_explain.log_level = notice");
                s.execute("SET client_min_messages = notice");
            }
            List<Integer> parts = new ArrayList<>();
            for (int i = 0; i < nParts; i++) parts.add(i);
            String in = String.join(",", Collections.nCopies(parts.size(), "?"));
            String keyQuery = "SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (" + in + ")"
                + " AND o.status = ? AND o.next_retry_at <= ?"
                + (filter ? " AND NOT EXISTS (SELECT 1 FROM outbox_record older WHERE older.record_key = o.record_key"
                    + " AND older.completed_at IS NULL AND (older.created_at < o.created_at"
                    + " OR (older.created_at = o.created_at AND older.id < o.id)))" : "")
                + " GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC";
            String cleanupQuery = "SELECT id FROM outbox_record WHERE status = ? AND completed_at < ? ORDER BY completed_at";
            Timestamp now = Timestamp.from(java.time.Instant.parse("2026-01-01T00:00:00Z"));
            Timestamp cutoff;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT percentile_disc(0.1) WITHIN GROUP (ORDER BY completed_at) FROM outbox_record WHERE status='COMPLETED'")) {
                rs.next(); cutoff = rs.getTimestamp(1);
            }
            for (int run = 1; run <= 7; run++) {
                try (PreparedStatement ps = c.prepareStatement(keyQuery)) {
                    int i = 1;
                    for (Integer p : parts) ps.setInt(i++, p);
                    ps.setString(i++, "NEW");
                    ps.setTimestamp(i, now);
                    ps.setMaxRows(maxRows);
                    report("key query run " + run + " maxRows=" + maxRows, ps);
                }
            }
            for (int lim : new int[]{maxRows, 0}) {
                try (PreparedStatement ps = c.prepareStatement(cleanupQuery)) {
                    ps.setString(1, "COMPLETED");
                    ps.setTimestamp(2, cutoff);
                    ps.setMaxRows(lim);
                    report("cleanup id query maxRows=" + lim, ps);
                }
            }
        }
    }

    static void report(String label, PreparedStatement ps) throws SQLException {
        int rows = 0;
        long t0 = System.nanoTime();
        try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rows++; }
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("=== %s -> client received %d rows in %.1f ms (incl. auto_explain overhead)%n", label, rows, ms);
        for (SQLWarning w = ps.getWarnings(); w != null; w = w.getNextWarning()) {
            for (String l : w.getMessage().split("\n")) {
                if (l.contains("Workers") || l.contains("Limit") || l.contains("actual") || l.contains("Duration"))
                    System.out.println("  " + (l.length() > 140 ? l.substring(0, 140) + " ..." : l));
            }
        }
    }
}
