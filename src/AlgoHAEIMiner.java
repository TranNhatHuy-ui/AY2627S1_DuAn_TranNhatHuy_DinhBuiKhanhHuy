import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

/**
 * Two-phase, Apriori-style baseline for High Average-Efficiency Itemset Mining, written in the same
 * style and on the same input format as the authors' AlgoHAEI (MAEI) so the two can be compared fairly.
 *
 * It follows the description of HAEIMiner (Yildirim, 2024) given in the MAEI paper (Sections 1 and 2.2):
 *   Phase I  - level-wise candidate generation using the anti-monotone aeub bound (Theorems 3.1 and 3.2),
 *              with ONE database scan per level to count aeub for all candidates of that level.
 *   Phase II - ONE further database scan that computes the exact average efficiency of every candidate
 *              and keeps those with ae(X) >= minae.
 *
 * NOTE: this is a re-implementation from the published description, not the authors' HAEIMiner code (which is
 * not publicly available). To keep the comparison fair it uses an in-memory copy of the database (so scans cost no
 * disk I/O), a prefix trie for subset counting, and the standard Apriori subset-pruning of candidates.
 */
public class AlgoHAEIMiner {

    /** Peak memory as sampled by checkMemory(), in MB (kept for parity with AlgoHAEI). */
    double maxMemory = 0;
    /** Wall-clock time (ms since epoch) when {@link #runAlgorithm} started. */
    long startTimestamp = 0;
    /** Wall-clock time (ms since epoch) when {@link #runAlgorithm} finished. */
    long endTimestamp = 0;
    /** Number of high average-efficiency itemsets found. */
    int haeiCount = 0;
    /** Number of database scans performed (one per level in Phase I, plus one for Phase II, plus the initial item scan). */
    long dbScans = 0;
    /** Total number of candidates whose aeub was counted in Phase I (level 2 and above). */
    long candidateCount = 0;
    /** The minimum average-efficiency threshold (minae) of the current run. */
    double min_AE = 0.0;
    /** Only the first maxTransactions transactions are used (for the scalability experiment). */
    public int maxTransactions = Integer.MAX_VALUE;
    /**
     * false (default): the database is read once and every later "scan" runs over the in-memory copy (optimistic for
     * the baseline: no disk I/O or parsing per scan).
     * true: every scan after the first re-reads and re-parses the database file, as the MAEI paper describes
     * HAEIMiner's repeated scanning (Section 1).
     */
    public boolean diskScan = false;
    /** Path of the database file, kept so that disk-scan mode can re-read it. */
    private String dbPath;
    /** Items that survived the aeub pruning of single items; only these are kept in the revised database. */
    private Set<Integer> keepItems;

    /** inv(i): total investment of each item, read from the stock file. */
    Map<Integer, Long> mapItemToINV = new HashMap<Integer, Long>();
    /** Writer of the output file; one line per HAEI. */
    BufferedWriter writer = null;

    /** The revised database: items of each transaction (surviving items only), sorted by item id. */
    int[][] txItems;
    /** Utilities aligned with {@link #txItems}. */
    int[][] txUtils;
    /** mu(Tj): maximum utility of any item in transaction Tj, taken before pruning (as in the authors' aeub). */
    int[] txMu;

    /** Trie node used for counting candidate itemsets inside transactions. */
    private static class Node {
        /** Child nodes keyed by item id; {@code null} for a leaf. */
        HashMap<Integer, Node> children;
        /** True when the path from the root to this node is a candidate itemset. */
        boolean terminal = false;
        /** Phase I accumulator: sum of mu(Tj) over the transactions containing the candidate. */
        long muSum = 0;
        /** Phase II accumulator: sum of u(X,Tj) / inv(X) over the transactions containing the candidate. */
        double aeSum = 0;
        /** inv(X) of the candidate. */
        double inv = 0;
        /** |X| of the candidate. */
        int size = 0;
        /** The candidate itemset (sorted item ids). */
        int[] itemset;
    }

    /**
     * Runs the algorithm. Same signature and file formats as AlgoHAEI.runAlgorithm.
     * @param input1  database file (items:TU:utilities per line)
     * @param input2  stock file (item investment per line)
     * @param output  file the HAEIs are written to
     * @param min_AE  minimum average-efficiency threshold
     */
    public void runAlgorithm(String input1, String input2, String output, double min_AE) throws IOException {
        maxMemory = 0;
        startTimestamp = System.currentTimeMillis();
        writer = new BufferedWriter(new FileWriter(output));
        this.min_AE = min_AE;
        this.dbPath = input1;

        // ---- read stock file ----
        BufferedReader myInput = new BufferedReader(new FileReader(input2));
        String line;
        while ((line = myInput.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] sp = line.split(" ");
            mapItemToINV.put(Integer.parseInt(sp[0]), Long.parseLong(sp[1]));
        }
        myInput.close();

        // ---- load the database into memory ----
        List<int[]> itemsList = new ArrayList<int[]>();
        List<int[]> utilsList = new ArrayList<int[]>();
        List<Integer> muList = new ArrayList<Integer>();
        myInput = new BufferedReader(new FileReader(input1));
        while ((line = myInput.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@') continue;
            if (itemsList.size() >= maxTransactions) break;
            String[] split = line.split(":");
            String[] items = split[0].split(" ");
            String[] us = split[2].split(" ");
            int[] it = new int[items.length];
            int[] ut = new int[items.length];
            int mu = Integer.MIN_VALUE;
            for (int i = 0; i < items.length; i++) {
                it[i] = Integer.parseInt(items[i]);
                ut[i] = Integer.parseInt(us[i]);
                if (ut[i] > mu) mu = ut[i];
            }
            itemsList.add(it);
            utilsList.add(ut);
            muList.add(mu);
        }
        myInput.close();
        int n = itemsList.size();
        txMu = new int[n];
        for (int i = 0; i < n; i++) txMu[i] = muList.get(i);

        // ---- scan 1: aeub of every single item, Pruning Strategy 1 ----
        dbScans++;
        Map<Integer, Long> muSumItem = new HashMap<Integer, Long>();
        for (int t = 0; t < n; t++) {
            for (int item : itemsList.get(t)) {
                Long v = muSumItem.get(item);
                muSumItem.put(item, v == null ? txMu[t] : v + txMu[t]);
            }
        }
        Set<Integer> keep = new HashSet<Integer>();
        for (Map.Entry<Integer, Long> e : muSumItem.entrySet()) {
            double aeub = (double) e.getValue() / mapItemToINV.get(e.getKey());
            if (aeub >= min_AE) keep.add(e.getKey());
        }

        // revised database: only surviving items, sorted by item id (the canonical order of the trie)
        txItems = new int[n][];
        txUtils = new int[n][];
        for (int t = 0; t < n; t++) {
            int[] it = itemsList.get(t), ut = utilsList.get(t);
            int cnt = 0;
            for (int x : it) if (keep.contains(x)) cnt++;
            Integer[] idx = new Integer[cnt];
            int c = 0;
            for (int i = 0; i < it.length; i++) if (keep.contains(it[i])) idx[c++] = i;
            final int[] itF = it;
            Arrays.sort(idx, new Comparator<Integer>() {
                public int compare(Integer a, Integer b) { return Integer.compare(itF[a], itF[b]); }
            });
            txItems[t] = new int[cnt];
            txUtils[t] = new int[cnt];
            for (int i = 0; i < cnt; i++) { txItems[t][i] = it[idx[i]]; txUtils[t][i] = ut[idx[i]]; }
        }
        itemsList = null; utilsList = null;
        keepItems = keep;
        checkMemory();

        // ---- Phase I: level-wise candidate generation with the aeub bound ----
        List<int[]> allCandidates = new ArrayList<int[]>();
        List<Integer> sortedKeep = new ArrayList<Integer>(keep);
        Collections.sort(sortedKeep);
        List<int[]> level = new ArrayList<int[]>();
        for (int item : sortedKeep) { level.add(new int[]{item}); allCandidates.add(new int[]{item}); }

        while (level.size() > 1) {
            List<int[]> candidates = generateCandidates(level);
            if (candidates.isEmpty()) break;
            candidateCount += candidates.size();
            Node root = buildTrie(candidates);
            dbScans++;
            rescanIfDisk();
            for (int t = 0; t < n; t++) countMu(root, txItems[t], 0, txMu[t]);
            List<int[]> next = new ArrayList<int[]>();
            for (int[] c : candidates) {
                Node nd = find(root, c);
                double aeub = (double) nd.muSum / nd.inv;
                if (aeub >= min_AE * (1 - 1e-12)) next.add(c);
            }
            allCandidates.addAll(next);
            level = next;
            checkMemory();
        }

        // ---- Phase II: one scan computing the exact ae of every candidate ----
        Node root = buildTrie(allCandidates);
        dbScans++;
        rescanIfDisk();
        for (int t = 0; t < n; t++) countAe(root, txItems[t], txUtils[t], 0, 0);
        for (int[] c : allCandidates) {
            Node nd = find(root, c);
            double ae = nd.aeSum / nd.size;
            if (ae >= min_AE) writeOut(c, ae);
        }
        checkMemory();
        writer.close();
        endTimestamp = System.currentTimeMillis();
    }

    /** In disk mode, re-reads and re-parses the database file (keeping only surviving items) to pay one scan's I/O cost. */
    private void rescanIfDisk() throws IOException {
        if (!diskScan) return;
        List<int[]> its = new ArrayList<int[]>(), uts = new ArrayList<int[]>();
        List<Integer> mus = new ArrayList<Integer>();
        BufferedReader in = new BufferedReader(new FileReader(dbPath));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '%' || line.charAt(0) == '@') continue;
            if (its.size() >= maxTransactions) break;
            String[] split = line.split(":");
            String[] items = split[0].split(" ");
            String[] us = split[2].split(" ");
            int mu = Integer.MIN_VALUE, cnt = 0;
            for (int i = 0; i < items.length; i++) {
                int u = Integer.parseInt(us[i]);
                if (u > mu) mu = u;
                if (keepItems.contains(Integer.parseInt(items[i]))) cnt++;
            }
            Integer[] idx = new Integer[cnt];
            int[] itAll = new int[items.length];
            for (int i = 0; i < items.length; i++) itAll[i] = Integer.parseInt(items[i]);
            int c = 0;
            for (int i = 0; i < items.length; i++) if (keepItems.contains(itAll[i])) idx[c++] = i;
            final int[] itF = itAll;
            Arrays.sort(idx, new Comparator<Integer>() {
                public int compare(Integer a, Integer b) { return Integer.compare(itF[a], itF[b]); }
            });
            int[] it = new int[cnt], ut = new int[cnt];
            for (int i = 0; i < cnt; i++) { it[i] = itAll[idx[i]]; ut[i] = Integer.parseInt(us[idx[i]]); }
            its.add(it); uts.add(ut); mus.add(mu);
        }
        in.close();
        int n = its.size();
        txItems = its.toArray(new int[n][]);
        txUtils = uts.toArray(new int[n][]);
        txMu = new int[n];
        for (int i = 0; i < n; i++) txMu[i] = mus.get(i);
    }

    /**
     * Standard Apriori join + subset-prune: itemsets are sorted arrays; two itemsets sharing their first k-1 items
     * are joined, and the result is kept only if all its k-subsets survived the previous level.
     *
     * @param level the surviving itemsets of the previous level, in lexicographic order
     * @return the candidate itemsets of the next level
     */
    private List<int[]> generateCandidates(List<int[]> level) {
        List<int[]> out = new ArrayList<int[]>();
        Set<String> prev = new HashSet<String>();
        for (int[] s : level) prev.add(Arrays.toString(s));
        int k = level.get(0).length;
        for (int i = 0; i < level.size(); i++) {
            for (int j = i + 1; j < level.size(); j++) {
                int[] a = level.get(i), b = level.get(j);
                boolean samePrefix = true;
                for (int x = 0; x < k - 1; x++) if (a[x] != b[x]) { samePrefix = false; break; }
                if (!samePrefix) break; // level is lexicographically sorted, so no later j can share the prefix
                int[] c = Arrays.copyOf(a, k + 1);
                c[k] = b[k - 1];
                boolean ok = true;
                for (int drop = 0; drop < k - 1 && ok; drop++) { // the two subsets that drop the last two items are a and b
                    int[] sub = new int[k];
                    int p = 0;
                    for (int x = 0; x <= k; x++) if (x != drop) sub[p++] = c[x];
                    if (!prev.contains(Arrays.toString(sub))) ok = false;
                }
                if (ok) out.add(c);
            }
        }
        return out;
    }

    /**
     * Builds a prefix trie of the given itemsets and fills in inv(X) and |X| at every terminal node.
     *
     * @param sets the candidate itemsets (sorted item ids)
     * @return the root of the trie
     */
    private Node buildTrie(List<int[]> sets) {
        Node root = new Node();
        for (int[] s : sets) {
            Node cur = root;
            double inv = 0;
            for (int item : s) {
                if (cur.children == null) cur.children = new HashMap<Integer, Node>();
                Node ch = cur.children.get(item);
                if (ch == null) { ch = new Node(); cur.children.put(item, ch); }
                cur = ch;
                inv += mapItemToINV.get(item);
            }
            cur.terminal = true;
            cur.inv = inv;
            cur.size = s.length;
            cur.itemset = s;
        }
        return root;
    }

    /**
     * Looks up the terminal node of an itemset that is known to be in the trie.
     *
     * @param root the trie root
     * @param s    the itemset (sorted item ids)
     * @return the node whose path spells the itemset
     */
    private Node find(Node root, int[] s) {
        Node cur = root;
        for (int item : s) cur = cur.children.get(item);
        return cur;
    }

    /**
     * Phase I counting: adds mu(Tj) to every candidate contained in the transaction, walking the trie along the
     * transaction's items.
     *
     * @param node  current trie node
     * @param items the transaction's items (sorted ascending)
     * @param start index in {@code items} from which to continue matching
     * @param mu    mu(Tj) of the transaction
     */
    private void countMu(Node node, int[] items, int start, int mu) {
        if (node.children == null) return;
        for (int p = start; p < items.length; p++) {
            Node ch = node.children.get(items[p]);
            if (ch != null) {
                if (ch.terminal) ch.muSum += mu;
                countMu(ch, items, p + 1, mu);
            }
        }
    }

    /**
     * Phase II counting: adds u(X,Tj) / inv(X) to every candidate contained in the transaction, carrying the running
     * utility of the matched items down the trie.
     *
     * @param node   current trie node
     * @param items  the transaction's items (sorted ascending)
     * @param utils  the utilities aligned with {@code items}
     * @param start  index in {@code items} from which to continue matching
     * @param uSoFar utility of the items matched so far along the current path
     */
    private void countAe(Node node, int[] items, int[] utils, int start, int uSoFar) {
        if (node.children == null) return;
        for (int p = start; p < items.length; p++) {
            Node ch = node.children.get(items[p]);
            if (ch != null) {
                int u = uSoFar + utils[p];
                if (ch.terminal) ch.aeSum += (double) u / ch.inv;
                countAe(ch, items, utils, p + 1, u);
            }
        }
    }

    /**
     * Writes one high average-efficiency itemset to the output file (same line format as the authors' code).
     *
     * @param itemset the itemset (sorted item ids)
     * @param ae      its average efficiency
     * @throws IOException if writing fails
     */
    private void writeOut(int[] itemset, double ae) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < itemset.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(itemset[i]);
        }
        sb.append("#AE: ").append(ae);
        writer.write(sb.toString());
        writer.newLine();
        haeiCount++;
    }

    /** Reads the current heap usage (MB) and keeps the largest value seen in {@link #maxMemory}. */
    private void checkMemory() {
        double currentMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024d / 1024d;
        if (currentMemory > maxMemory) maxMemory = currentMemory;
    }

    /** Prints the running time, memory figure, number of HAEIs, database scans and candidates to standard output. */
    public void printStats() {
        System.out.println("=============  HAEIMiner (two-phase baseline) - STATS =============");
        System.out.println(" Total time ~ " + (endTimestamp - startTimestamp) + " ms");
        System.out.println(" Memory ~ " + maxMemory + " MB");
        System.out.println(" High average-efficiency itemsets count : " + haeiCount);
        System.out.println(" Database scans : " + dbScans);
        System.out.println(" Candidates counted in Phase I : " + candidateCount);
    }
}
