import java.io.*;
import java.util.*;

/**
 * Client class: runs ONE configuration of ONE algorithm in a fresh JVM and prints a single JSON line.
 *
 * Usage: java RunOne -algo MAEI|HAEIMiner -data <DBUtility> -stock <stock> -minae <value>
 *                    [-maep on|off] [-eaecp on|off] [-lazy on|off (lazy EAECS)] [-order AEUB_ASC|AEUB_DESC|INVESTMENT_ASC|RANDOM]
 *                    [-traversal DFS|BFS] [-disk on|off (HAEIMiner only)] [-frac 0.2..1.0] [-out <file>]
 *
 * Reported fields:
 *   runtime_ms        algorithm time as measured by the authors' own start/end timestamps (includes reading
 *                     the input and writing the output, as in the authors' code)
 *   peak_heap_mb      peak heap in use, sampled every 5 ms by a background thread (includes not-yet-collected garbage)
 *   baseline_heap_mb  heap in use right before the run (after a GC)
 *   join_count        number of AE-list joins (MAEI) or number of database scans (HAEIMiner)
 *   haei_count        number of high average-efficiency itemsets found
 *   candidates        (HAEIMiner only) candidates counted in Phase I
 *   ae_min, ae_max, ae_mean, ae_sum
 *                     minimum, maximum, mean and sum of the average efficiency ae(X) over the itemsets found
 *   eff_max, eff_mean maximum and mean of the efficiency e(X) = ae(X) * |X| (the utility-to-investment ratio)
 *   size_mean, size_max
 *                     mean and maximum number of items of the itemsets found
 *   itemset_hash      order-independent 64-bit fingerprint of the SET of itemsets found; two runs found exactly the
 *                     same itemsets if and only if (up to hash collisions) their fingerprints are equal
 *   eaecs_ms          time spent on EAECS: building it in the second scan (eager) or computing pairs on demand (-lazy on); 0 without EAECP and for HAEIMiner
 * These values are read back from the output file AFTER the timed run and the heap sampler have finished, so they
 * do not influence the measured runtime or memory.
 */
public class RunOne {
    /**
     * Parses the command line, runs the selected algorithm once while sampling heap usage, and prints one JSON line.
     *
     * @param args pairs of "-option value" as listed in the class description
     * @throws Exception if the input cannot be read or an option value is invalid
     */
    public static void main(String[] args) throws Exception {
        // Step 1: read the options
        Map<String, String> a = new HashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        String algo = a.getOrDefault("-algo", "MAEI");
        String data = a.get("-data"), stock = a.get("-stock");
        double minae = Double.parseDouble(a.get("-minae"));
        double frac = Double.parseDouble(a.getOrDefault("-frac", "1.0"));
        int maxTx = Integer.MAX_VALUE;
        if (frac < 1.0) {
            int total = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(data))) {
                String l;
                while ((l = br.readLine()) != null) {
                    if (!l.isEmpty() && l.charAt(0) != '#' && l.charAt(0) != '%' && l.charAt(0) != '@') total++;
                }
            }
            maxTx = (int) Math.ceil(total * frac);
        }
        // Step 2: choose the output file and start the heap sampler (peak heap in use, polled every 5 ms)
        File out = a.containsKey("-out") ? new File(a.get("-out")) : File.createTempFile("haei", ".txt");
        if (!a.containsKey("-out")) out.deleteOnExit();

        final Runtime rt = Runtime.getRuntime();
        System.gc();
        final long baseline = rt.totalMemory() - rt.freeMemory();
        final long[] peak = {baseline};
        Thread sampler = new Thread(new Runnable() {
            public void run() {
                while (!Thread.currentThread().isInterrupted()) {
                    long used = rt.totalMemory() - rt.freeMemory();
                    if (used > peak[0]) peak[0] = used;
                    try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        // Step 3: run the selected algorithm (a server class) with the selected switches
        long runtime, joins, haei, cand = 0;
        double eaecsMs = 0;
        if (algo.equalsIgnoreCase("HAEIMiner")) {
            AlgoHAEIMiner m = new AlgoHAEIMiner();
            m.maxTransactions = maxTx;
            m.diskScan = a.getOrDefault("-disk", "off").equalsIgnoreCase("on");
            m.runAlgorithm(data, stock, out.getPath(), minae);
            runtime = m.endTimestamp - m.startTimestamp;
            joins = m.dbScans; haei = m.haeiCount; cand = m.candidateCount;
        } else {
            AlgoHAEI m = new AlgoHAEI();
            m.maxTransactions = maxTx;
            m.useMAEP = !a.getOrDefault("-maep", "on").equalsIgnoreCase("off");
            m.useEAECP = !a.getOrDefault("-eaecp", "on").equalsIgnoreCase("off");
            m.lazyEAECS = a.getOrDefault("-lazy", "off").equalsIgnoreCase("on");
            m.useBFS = a.getOrDefault("-traversal", "DFS").equalsIgnoreCase("BFS");
            m.orderStrategy = AlgoHAEI.OrderStrategy.valueOf(a.getOrDefault("-order", "AEUB_ASC").toUpperCase());
            m.runAlgorithm(data, stock, out.getPath(), minae);
            runtime = m.endTimestamp - m.startTimestamp;
            joins = m.joinCount; haei = m.haeiCount;
            eaecsMs = m.eaecsNanos / 1e6;
        }
        // Step 4: stop the sampler and print the result as one JSON line
        sampler.interrupt();
        AeStats st = readAeStats(out);
        double n = Math.max(1, st.count);
        System.out.println(String.format(Locale.ROOT,
            "{\"runtime_ms\": %d, \"peak_heap_mb\": %.2f, \"baseline_heap_mb\": %.2f, \"join_count\": %d, \"haei_count\": %d, \"candidates\": %d, "
            + "\"ae_min\": %.17g, \"ae_max\": %.17g, \"ae_mean\": %.17g, \"ae_sum\": %.17g, \"eff_max\": %.17g, \"eff_mean\": %.17g, "
            + "\"size_mean\": %.4f, \"size_max\": %d, \"itemset_hash\": %d, \"eaecs_ms\": %.2f}",
            runtime, peak[0] / 1048576.0, baseline / 1048576.0, joins, haei, cand,
            st.count == 0 ? 0 : st.aeMin, st.aeMax, st.aeSum / n, st.aeSum, st.effMax, st.effSum / n,
            st.sizeSum / n, st.sizeMax, st.hash, eaecsMs));
    }

    /** Summary of the efficiency values and itemsets found in one output file. */
    private static final class AeStats {
        /** Number of itemsets. */
        long count = 0;
        /** Smallest, largest and total average efficiency ae(X). */
        double aeMin = Double.MAX_VALUE, aeMax = 0, aeSum = 0;
        /** Largest and total efficiency e(X) = ae(X) * |X|. */
        double effMax = 0, effSum = 0;
        /** Total and largest itemset size. */
        long sizeSum = 0;
        int sizeMax = 0;
        /** Order-independent fingerprint of the set of itemsets. */
        long hash = 0;
    }

    /**
     * Reads an output file (one "item item ...#AE: value" line per itemset, as written by both algorithms) and summarises it.
     *
     * @param f the output file
     * @return the summary statistics and the itemset-set fingerprint
     * @throws IOException if the file cannot be read
     */
    private static AeStats readAeStats(File f) throws IOException {
        AeStats s = new AeStats();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                int k = line.indexOf("#AE:");
                if (k < 0) continue;
                // Step 1: parse the itemset (sorted, so the order in which items were joined does not matter) and its ae value
                String[] parts = line.substring(0, k).trim().split(" ");
                int[] ids = new int[parts.length];
                for (int i = 0; i < ids.length; i++) ids[i] = Integer.parseInt(parts[i]);
                Arrays.sort(ids);
                double ae = Double.parseDouble(line.substring(k + 4).trim());
                // Step 2: update the statistics and the fingerprint (sum of per-itemset hashes = independent of line order)
                long h = 0x9E3779B97F4A7C15L;
                for (int id : ids) h = mix(h ^ id);
                s.hash += h;
                s.count++;
                s.aeMin = Math.min(s.aeMin, ae); s.aeMax = Math.max(s.aeMax, ae); s.aeSum += ae;
                double eff = ae * ids.length;
                s.effMax = Math.max(s.effMax, eff); s.effSum += eff;
                s.sizeSum += ids.length; s.sizeMax = Math.max(s.sizeMax, ids.length);
            }
        }
        return s;
    }

    /**
     * 64-bit mixing function (splitmix64 finaliser) used to build the itemset fingerprint.
     *
     * @param z the value to mix
     * @return the mixed value
     */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
