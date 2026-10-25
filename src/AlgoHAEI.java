/*
 * @author Xuanwei Zhang
 * @date 2025/12/026
 * @description: MAEI Algorithm
 * @Version: v1.0
 *
 * MODIFIED FOR AN ABLATION STUDY (see README.md and AlgoHAEI.functional.diff for every change).
 * All ablation switches default to the authors' original behaviour, so with default
 * options this class computes exactly what the original AlgoHAEI.java computes.
 * */


import java.io.*;
import java.util.*;

/**
 * Server class: the MAEI algorithm (Li, Zhang, Shang, Chen, EAAI 166 (2026) 113639) for mining high
 * average-efficiency itemsets (HAEIs), in the authors' implementation plus ablation switches.
 *
 * <p>An itemset X is a HAEI when ae(X) = sum over transactions T containing X of u(X,T) / (inv(X) * |X|)
 * is at least the user threshold minae. The algorithm builds an AE-List per item (two database scans), then
 * explores the itemset enumeration tree depth-first, joining AE-Lists and pruning with three strategies:
 * Pruning Strategy 1 (aeub of single items), Pruning Strategy 2 (MAEP, the mae(X) bound) and Pruning
 * Strategy 3 (EAECP, the co-occurrence structure EAECS).</p>
 *
 * <p>Usage: create the object, optionally set the public ablation fields, call
 * {@link #runAlgorithm(String, String, String, double)}, then read the statistics fields or call
 * {@link #printStats()}.</p>
 */
public class AlgoHAEI {
    // algorithm parameters
    /** Largest heap usage (MB) observed by {@link #checkMemory()}; sampled only before and after mining. */
    double maxMemory = 0;
    /** Wall-clock time (ms since epoch) when {@link #runAlgorithm} started; includes reading the input. */
    long startTimestamp = 0;
    /** Wall-clock time (ms since epoch) when {@link #runAlgorithm} finished; includes writing the output. */
    long endTimestamp = 0;
    /** Number of high average-efficiency itemsets written to the output file. */
    int haeiCount = 0;
    /** Number of AE-List joins performed by {@link #construct}; the main measure of search effort. */
    int joinCount = 0;
    /** The minimum average-efficiency threshold (minae) of the current run. */
    double min_AE = 0.0;

    // ---- ABLATION OPTIONS (defaults = authors' original algorithm) ----
    /** Item ordering strategies. AEUB_ASC is the paper's own (Algorithm 1, line 4). */
    public enum OrderStrategy { AEUB_ASC, AEUB_DESC, INVESTMENT_ASC, RANDOM }
    /** Pruning Strategy 2 (MAEP). When false, the looser aeub(X) bound (Definition 3.8) is used instead. */
    public boolean useMAEP = true;
    /** Pruning Strategy 3 (EAECP). When false, EAECS is neither built nor queried. */
    public boolean useEAECP = true;
    /**
     * ABLATION ADDITION (lazy EAECS). Only used when {@link #useEAECP} is true. false = the authors' way: the whole co-occurrence
     * structure is built during the second database scan. true = nothing is built in the scan; the value EAECS(x,y) is computed
     * the first time the search asks for the pair (x,y), by merging the AE-Lists of the single items x and y, and then cached.
     * Both ways give exactly the same values, so the same pairs are skipped and the same joins are made.
     */
    public boolean lazyEAECS = false;
    /** Time (nanoseconds) spent on EAECS: building it in the second scan (eager) or computing pairs on demand (lazy). 0 without EAECP. */
    long eaecsNanos = 0;
    /** Number of pair values computed on demand (lazy EAECS only). */
    long eaecsLazyPairs = 0;
    /** Lazy EAECS: the AE-List of every single item (kept after the second scan). */
    Map<Integer,UtilityList> singletonULs;
    /** Lazy EAECS: for every transaction, the maximum utility among the items that survived Pruning Strategy 1 (what the eager scan uses as mu). */
    double[] prunedMu;
    /** Search order: false = depth-first (the paper's), true = breadth-first. */
    public boolean useBFS = false;
    /** Item ordering used for AE-List construction and for the search tree (default: the paper's AEUB_ASC). */
    public OrderStrategy orderStrategy = OrderStrategy.AEUB_ASC;
    /** Only the first maxTransactions transactions are used (for the scalability experiment). */
    public int maxTransactions = Integer.MAX_VALUE;
    /** Seed of the RANDOM order, fixed so that runs are reproducible. */
    long randomSeed = 42L;
    /** Sort key of every surviving item under {@link #orderStrategy} (smaller key = earlier in the order). */
    Map<Integer,Double> mapItemToOrderKey;
    /** mu(Tj) for every transaction in file order, collected during the first scan (growable form of {@link #tidMu}). */
    List<Integer> tidMuList = new ArrayList<Integer>();
    /** mu(Tj), the maximum utility of any item in transaction Tj, indexed by tid; used by {@link #calAeub}. */
    int[] tidMu;

    // data structure
    /** The AE-Lists of all single items that survived Pruning Strategy 1, in the chosen item order. */
    List<UtilityList> listOfUtilityLists = new ArrayList<UtilityList>();
    /** inv(i): total investment of each item, read from the stock file. */
    Map<Integer,Long> mapItemToINV;
    /** aeub(i) of each item (Definition 3.8), used by Pruning Strategy 1 and for ordering. */
    Map<Integer,Double> mapItemToAEUB;
    /** Sum of mu(Tj) over the transactions containing each item (the numerator of aeub). */
    Map<Integer,Long> mapItemToMu;
    /** Writer of the output file; one line per HAEI. */
    BufferedWriter writer = null;
    /** EAECS (Estimated Average-Efficiency Co-occurrence Structure): item x -> (item y -> aeub of the pair {x,y}). */
    Map<Integer,Map<Integer,Double>> mapItemToEAES;


    // data structure for HAEI
    /** One (item, utility) entry of a revised transaction, with the item's investment. */
    class Pair{
        /** The item id. */
        int item = 0;
        /** The utility of the item in the transaction. */
        int utility = 0;
        /** The item's investment inv(i) (named rMinInv because it feeds the "minimum remaining investment"). */
        long rMinInv = 0;
    }

    /** Creates the algorithm object with the authors' default behaviour (MAEP and EAECP on, DFS, aeub-ascending order). */
    public AlgoHAEI(){
    }

    /**
     * Runs the whole algorithm: two database scans to build the AE-Lists, then the recursive (or breadth-first)
     * mining. Every HAEI found is written to {@code output} as "item item ...#AE: value".
     *
     * @param input1  path of the database file; one transaction per line, "items:TU:utilities" (SPMF utility format)
     * @param intput2 path of the stock file; one "item investment" pair per line (name kept from the original code)
     * @param output  path of the file the HAEIs are written to
     * @param min_AE  the minimum average-efficiency threshold minae
     * @throws IOException if an input file cannot be read or the output file cannot be written
     */
    public void runAlgorithm(String input1, String intput2, String output, double min_AE) throws IOException{
        // Step 1: start the timer, open the output file and create the empty data structures
        startTimestamp = System.currentTimeMillis();
        writer = new BufferedWriter(new FileWriter(output));
        mapItemToAEUB = new HashMap<Integer,Double>();
        mapItemToINV = new HashMap<Integer,Long>();
        mapItemToMu = new HashMap<Integer,Long>();
        mapItemToEAES = new HashMap<Integer,Map<Integer,Double>>();
        this.min_AE = min_AE;
        BufferedReader myInput = null;
        String thisLine;

        // Step 2: read the stock file (item -> total investment inv(i))
        try{
            myInput = new BufferedReader(new InputStreamReader(new FileInputStream(new File(intput2))));
            while ((thisLine = myInput.readLine()) != null) {
                if (thisLine.isEmpty() == true ||
                        thisLine.charAt(0) == '#' || thisLine.charAt(0) == '%'
                        || thisLine.charAt(0) == '@') {
                    continue;
                }
                String[] str = thisLine.split(" ");
                Integer item = Integer.valueOf(str[0]);
                Long invest = Long.parseLong(str[1]);
                mapItemToINV.put(item,invest);
            }
        }catch (Exception e) {
            e.printStackTrace();
        }finally {
            if(myInput != null){
                myInput.close();
            }
        }


        // Step 3 (first database scan): for every transaction find mu(Tj), its maximum utility, and add it to the
        // mu-sum of every item in the transaction
        try {
            myInput = new BufferedReader(new InputStreamReader(new FileInputStream(new File(input1))));
            while ((thisLine = myInput.readLine()) != null){
                if (thisLine.isEmpty() == true || thisLine.charAt(0) == '#') {
                    continue;
                }
                if (tidMuList.size() >= maxTransactions) break;
                String[] split = thisLine.split(":");
                String[] items = split[0].split(" ");
                String[] utilityValues = split[2].split(" ");
                Integer transactionMUtility = Integer.MIN_VALUE;
                for (int i = 0; i < utilityValues.length; i++) {
                    if (transactionMUtility < Integer.parseInt(utilityValues[i])) {
                        transactionMUtility = Integer.parseInt(utilityValues[i]);
                    }
                }
                tidMuList.add(transactionMUtility);
                for(int i = 0; i < items.length; i++){
                    Integer item = Integer.parseInt(items[i]);
                    Long mu = mapItemToMu.get(item);
                    mu = (mu == null)?  transactionMUtility: mu + transactionMUtility;
                    mapItemToMu.put(item, mu);
                }
            }
        }catch (Exception e) {
            e.printStackTrace();
        }finally {
            if(myInput != null){
                myInput.close();
            }
        }

        // Step 4: aeub(i) = (sum of mu(Tj) over the transactions containing i) / inv(i)   (Definition 3.8)
        //  Calculate AEUB
        for (Integer item : mapItemToMu.keySet()) {
            Double aeub = mapItemToAEUB.get(item);
            double newaeub = (double)mapItemToMu.get(item) / mapItemToINV.get(item);
            aeub = (aeub == null)? newaeub : newaeub + aeub;
            mapItemToAEUB.put(item, aeub);
        }

        List<Integer> ItemLists = new ArrayList<Integer>();

        // Step 5: Pruning Strategy 1 - keep only the items whose aeub reaches minae
        //AEUB Pruning Strategy
        for(Integer item : mapItemToAEUB.keySet()){
            if(mapItemToAEUB.get(item) >= min_AE){
                ItemLists.add(item);
            }
        }

        // Step 6: freeze mu(Tj) into an array and sort the surviving items in the chosen item order
        tidMu = new int[tidMuList.size()];
        for (int i = 0; i < tidMu.length; i++) tidMu[i] = tidMuList.get(i);
        buildOrderKeys(ItemLists);

        Collections.sort(ItemLists, new Comparator<Integer>() {
            @Override
            public int compare(Integer o1, Integer o2) {
                return compareByOrder(o1, o2);
            }
        });


        // Step 7: create an empty AE-List for every surviving item
        Map<Integer,UtilityList> mapItemToUtility = new HashMap<Integer,UtilityList>();

        for(Integer item : ItemLists){
            UtilityList utilityList = new UtilityList(item);
            utilityList.addInvestion(mapItemToINV.get(item));
            mapItemToUtility.put(item, utilityList);
        }

        // Step 8 (second database scan): rewrite each transaction without the pruned items, sort it, and add one
        // element (tid, u, mru, minrinv, rn) to the AE-List of each of its items; also accumulate EAECS
        try {
            myInput = new BufferedReader(new InputStreamReader(new FileInputStream(new File(input1))));
            int tid = 0;
            if (useEAECP && lazyEAECS) prunedMu = new double[tidMu.length];
            while ((thisLine = myInput.readLine()) != null) {
                if (thisLine.isEmpty() == true || thisLine.charAt(0) == '#') {
                    continue;
                }
                if (tid >= maxTransactions) break;
                String[] split = thisLine.split(":");
                String[] items = split[0].split(" ");
                String[] utilityValues = split[2].split(" ");


                double maxUtilityOfCurrentTranscation = 0;

                //  revised transaction
                List<Pair> revisedTransaction = new ArrayList<Pair>();
                for (int i = 0; i < items.length; i++){
                    Pair pair = new Pair();
                    pair.item = Integer.parseInt(items[i]);
                    pair.utility = Integer.parseInt(utilityValues[i]);
                    pair.rMinInv = mapItemToINV.get(pair.item);
                    if(mapItemToAEUB.get(pair.item) >= min_AE){
                        revisedTransaction.add(pair);
                        if(maxUtilityOfCurrentTranscation < pair.utility){
                            maxUtilityOfCurrentTranscation = pair.utility;
                        }
                    }
                }

                Collections.sort(revisedTransaction, new Comparator<Pair>() {
                    @Override
                    public int compare(Pair o1, Pair o2) {
                        return compareByOrder(o1.item, o2.item);
                    }
                });

                // Construct the initial AE-List
                for(int i = 0;i < revisedTransaction.size(); i++){
                    Pair pair = revisedTransaction.get(i);
                    int rmu = 0;
                    long rmi = pair.rMinInv;
                    for(int j = i+1; j < revisedTransaction.size(); j++){
                        Pair pair1 = revisedTransaction.get(j);
                        if(pair1.utility > rmu) {
                            rmu = pair1.utility;
                        }
                        if(pair1.rMinInv < rmi) {
                            rmi = pair1.rMinInv;
                        }
                    }

                    int mn = revisedTransaction.size() - 1 - i;
                    UtilityList utilityListOfItem = mapItemToUtility.get(pair.item);
                    Element element = new Element(tid, pair.utility, rmu, rmi,mn);
                    utilityListOfItem.addElement(element,mn);
                }
                // EAECS (eager, the authors' way): for every pair of items of the revised transaction add mu(T) / (inv(x) + inv(y)).
                // Kept in a loop of its own (the values are the same as inside the AE-List loop) so that its time can be measured
                // with two clock readings per transaction instead of two per item.
                if (useEAECP && lazyEAECS) {
                    prunedMu[tid] = maxUtilityOfCurrentTranscation;
                } else if (useEAECP) {
                    long eaecsStart = System.nanoTime();
                    for(int i = 0;i < revisedTransaction.size(); i++){
                        Pair pair = revisedTransaction.get(i);
                        Map<Integer,Double> mapEAESItem = mapItemToEAES.get(pair.item);
                        if(mapEAESItem == null){
                            mapEAESItem = new HashMap<Integer,Double>();
                            mapItemToEAES.put(pair.item,mapEAESItem);
                        }
                        for(int j = i+1;j < revisedTransaction.size();j++){
                            Pair pairAfter = revisedTransaction.get(j);
                            Double invOfItem =(double)(mapItemToINV.get(pair.item) + mapItemToINV.get(pairAfter.item));
                            Double aeubSum = mapEAESItem.get(pairAfter.item);
                            if(aeubSum == null) {
                                mapEAESItem.put(pairAfter.item, maxUtilityOfCurrentTranscation / invOfItem);
                            }else {
                                mapEAESItem.put(pairAfter.item,aeubSum + maxUtilityOfCurrentTranscation / invOfItem);
                            }
                        }
                    }
                    eaecsNanos += System.nanoTime() - eaecsStart;
                }
                tid++;
            }
        }catch (Exception e) {
            e.printStackTrace();
        }finally {
            if(myInput != null){
                myInput.close();
            }
        }

        singletonULs = mapItemToUtility;
        // Step 9: collect the AE-Lists and sort them in the item order
        listOfUtilityLists.addAll(mapItemToUtility.values());

        Collections.sort(listOfUtilityLists, new Comparator<UtilityList>() {
            @Override
            public int compare(UtilityList o1, UtilityList o2) {
                return compareByOrder(o1.item, o2.item);
            }
        });

        // Step 10: mine the search tree (depth-first = Algorithm 2 of the paper; breadth-first = ablation option)
        checkMemory();
        if (useBFS) {
            HAEIMineBFS(listOfUtilityLists, min_AE);
        } else {
            HAEIMine(new int[0],null,listOfUtilityLists, min_AE);
        }
        checkMemory();
        writer.close();
        endTimestamp = System.currentTimeMillis();
    }


    /**
     * Pruning Strategy 3 (EAECP): may the join of X (last item x) and Y (last item y) still lead to a HAEI? With EAECS built in the scan
     * (the authors' way) this is a table look-up; with {@link #lazyEAECS} the pair value is computed the first time it is asked for.
     * Without EAECP, or for an item that has no row in EAECS, every pair is allowed.
     *
     * @param x last item of X
     * @param y last item of Y (later than x in the item order)
     * @return false if EAECS(x,y) is missing or below minae, so the join can be skipped
     */
    private boolean eaecsAllows(int x, int y) {
        if (lazyEAECS && useEAECP) return lazyAllows(x, y);
        Map<Integer,Double> mapEAES = mapItemToEAES.get(x);
        if (mapEAES != null) {
            Double aeubY = mapEAES.get(y);
            if (aeubY == null || aeubY < min_AE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lazy EAECS look-up: returns the cached value of EAECS(x,y), computing it first if the pair has not been asked for before.
     * The eager structure has a row for every item that occurs in the data, and a missing entry means "x and y never occur together";
     * the same two rules are applied here, so the answers are identical to the eager ones.
     *
     * @param x last item of X
     * @param y last item of Y
     * @return false if the pair is rejected by EAECP
     */
    private boolean lazyAllows(int x, int y) {
        UtilityList lx = singletonULs.get(x);
        UtilityList ly = singletonULs.get(y);
        if (lx == null || ly == null || lx.elements.isEmpty()) return true;
        Map<Integer,Double> row = mapItemToEAES.get(x);
        if (row == null) {
            row = new HashMap<Integer,Double>();
            mapItemToEAES.put(x, row);
        }
        Double v = row.get(y);
        if (v == null) {
            long t0 = System.nanoTime();
            v = lazyPairValue(x, y, lx, ly);
            row.put(y, v);
            eaecsNanos += System.nanoTime() - t0;
            eaecsLazyPairs++;
        }
        return !(v.isNaN() || v < min_AE);
    }

    /**
     * Computes EAECS(x,y) = sum over the transactions that contain both x and y of mu(T) / (inv(x) + inv(y)), where mu(T) is the
     * maximum utility of the items of T that survived Pruning Strategy 1. The common transactions are found by walking the two
     * AE-Lists of the single items in tid order (or, if one list is much shorter, by binary search in the longer one), and the terms are
     * added in tid order, exactly as the eager scan adds them, so the result is bit-for-bit the same.
     *
     * @param x  the earlier item
     * @param y  the later item
     * @param lx AE-List of x
     * @param ly AE-List of y
     * @return the value, or NaN if x and y never occur together
     */
    private double lazyPairValue(int x, int y, UtilityList lx, UtilityList ly) {
        double invSum = (double)(mapItemToINV.get(x) + mapItemToINV.get(y));
        List<Element> a = lx.elements, b = ly.elements;
        double sum = 0;
        boolean any = false;
        if (a.size() * 8L < b.size() || b.size() * 8L < a.size()) {
            List<Element> small = a.size() <= b.size() ? a : b;
            List<Element> large = small == a ? b : a;
            for (int i = 0; i < small.size(); i++) {
                int tid = small.get(i).tid;
                boolean found = false;
                int lo = 0, hi = large.size() - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int t = large.get(mid).tid;
                    if (t < tid) lo = mid + 1; else if (t > tid) hi = mid - 1; else { found = true; break; }
                }
                if (found) {
                    double term = prunedMu[tid] / invSum;
                    sum = any ? sum + term : term;
                    any = true;
                }
            }
        } else {
            int i = 0, j = 0;
            while (i < a.size() && j < b.size()) {
                int ta = a.get(i).tid, tb = b.get(j).tid;
                if (ta == tb) {
                    double term = prunedMu[ta] / invSum;
                    sum = any ? sum + term : term;
                    any = true;
                    i++; j++;
                } else if (ta < tb) i++; else j++;
            }
        }
        return any ? sum : Double.NaN;
    }

    /**
     * Depth-first mining (Algorithm 2 of the paper). For every itemset X in {@code ULs}: output it if it is a HAEI,
     * compute its pruning bound, and if the bound reaches minae join X with each later itemset Y (after the EAECS
     * check) and recurse on the extensions.
     *
     * @param prefix the items of the prefix itemset P (empty at the root)
     * @param pUL    the AE-List of P, or {@code null} when P is empty
     * @param ULs    the AE-Lists of the itemsets Px that extend P, in the item order
     * @param min_AE the minimum average-efficiency threshold minae
     * @throws IOException if writing a result fails
     */
    private void HAEIMine(int[] prefix,UtilityList pUL,List<UtilityList> ULs,double min_AE) throws IOException {
        for (int i = 0; i < ULs.size(); i++){
            UtilityList X = ULs.get(i);
            int length = prefix.length +1;
            // Step 1: if ae(X) >= minae, X is a high average-efficiency itemset, so output it
            if (X.Sumae /length >= min_AE){
                writeOut(prefix, X.item ,X.Sumae /length);
            }

            // Calculate the MAE of X
            double Mae;
            if(X.mn == 0) {
                Mae = 0;
            } else{
                // ablation switch: tight mae bound (MAEP) or the looser aeub bound
                Mae = useMAEP ? calMae(X, length) : calAeub(X);
            }

            // Step 2: Pruning Strategy 2 - extend X only if the upper bound of its supersets reaches minae
            // MAE Pruning Strategy
            if (Mae >= min_AE){
                List<UtilityList> exULs = new ArrayList<UtilityList>();
                for (int j = i+1; j < ULs.size(); j++){
                    UtilityList Y = ULs.get(j);
                    // Step 3: Pruning Strategy 3 - skip the pair (X, Y) if EAECS says it cannot reach minae
                    //EAES-Prune
                    if (!eaecsAllows(X.item, Y.item)) {
                        continue;
                    }
                    exULs.add(construct(pUL,X,Y));
                }

                int [] newPrefix = new int[prefix.length+1];
                System.arraycopy(prefix, 0, newPrefix, 0, prefix.length);
                newPrefix[prefix.length] = X.item;
                // Step 4: recurse on the extensions of X
                // Recursive Mining
                HAEIMine(newPrefix, X, exULs, min_AE);
            }
        }
    }

    /**
     * ABLATION ADDITION. Breadth-first version of HAEIMine: identical per-itemset logic, but
     * child tasks are queued instead of recursed into, so the search tree is explored level by level.
     *
     * @param rootULs the AE-Lists of the single items, in the item order
     * @param min_AE  the minimum average-efficiency threshold minae
     * @throws IOException if writing a result fails
     */
    private void HAEIMineBFS(List<UtilityList> rootULs, double min_AE) throws IOException {
        Deque<Object[]> queue = new ArrayDeque<Object[]>();
        queue.add(new Object[]{new int[0], null, rootULs});
        while (!queue.isEmpty()) {
            Object[] task = queue.poll();
            int[] prefix = (int[]) task[0];
            UtilityList pUL = (UtilityList) task[1];
            @SuppressWarnings("unchecked")
            List<UtilityList> ULs = (List<UtilityList>) task[2];
            for (int i = 0; i < ULs.size(); i++) {
                UtilityList X = ULs.get(i);
                int length = prefix.length + 1;
                if (X.Sumae / length >= min_AE) {
                    writeOut(prefix, X.item, X.Sumae / length);
                }
                double Mae;
                if (X.mn == 0) {
                    Mae = 0;
                } else {
                    Mae = useMAEP ? calMae(X, length) : calAeub(X);
                }
                if (Mae >= min_AE) {
                    List<UtilityList> exULs = new ArrayList<UtilityList>();
                    for (int j = i + 1; j < ULs.size(); j++) {
                        UtilityList Y = ULs.get(j);
                        if (!eaecsAllows(X.item, Y.item)) {
                            continue;
                        }
                        exULs.add(construct(pUL, X, Y));
                    }
                    if (!exULs.isEmpty()) {
                        int[] newPrefix = new int[prefix.length + 1];
                        System.arraycopy(prefix, 0, newPrefix, 0, prefix.length);
                        newPrefix[prefix.length] = X.item;
                        queue.add(new Object[]{newPrefix, X, exULs});
                    }
                }
            }
        }
    }

    /**
     * ABLATION ADDITION. The looser upper bound used when MAEP is switched off: aeub(X) of
     * Definition 3.8, i.e. the sum over X's transactions of the transaction's maximum utility,
     * divided by inv(X). By Theorems 3.1 and 3.2 it bounds the average efficiency of X and of every
     * superset of X, so pruning with it is still lossless; it is just weaker than mae(X).
     *
     * @param ul the AE-List of itemset X
     * @return aeub(X)
     */
    private double calAeub(UtilityList ul){
        double sum = 0;
        for (int i = 0; i < ul.elements.size(); i++) {
            sum += tidMu[ul.elements.get(i).tid];
        }
        return sum / ul.inv;
    }

    /**
     * ABLATION ADDITION. Strict total order on items: first by the order key, then by item id.
     * The tie-break matters because the item order is applied in three separate sorts (global item list,
     * every transaction, list of utility lists); if two items had equal keys and were left in different
     * relative orders in those places, rn / mru / minrinv would be computed from an inconsistent order and
     * the pruning bounds would no longer be valid. With the authors' aeub order ties essentially never occur,
     * so this does not change their behaviour; it only makes other orders (e.g. by investment) safe.
     *
     * @param a first item id
     * @param b second item id
     * @return negative, zero or positive as item a comes before, equals or comes after item b in the item order
     */
    private int compareByOrder(Integer a, Integer b) {
        int c = mapItemToOrderKey.get(a).compareTo(mapItemToOrderKey.get(b));
        return c != 0 ? c : Integer.compare(a, b);
    }

    /**
     * ABLATION ADDITION. Builds the sort key for each surviving item according to orderStrategy.
     * For AEUB_ASC the key is the aeub value itself, which reproduces the original sort exactly.
     *
     * @param items the items that survived Pruning Strategy 1
     */
    private void buildOrderKeys(List<Integer> items) {
        mapItemToOrderKey = new HashMap<Integer,Double>();
        switch (orderStrategy) {
            case AEUB_ASC:
                for (Integer it : items) mapItemToOrderKey.put(it, mapItemToAEUB.get(it));
                break;
            case AEUB_DESC:
                for (Integer it : items) mapItemToOrderKey.put(it, -mapItemToAEUB.get(it));
                break;
            case INVESTMENT_ASC:
                for (Integer it : items) mapItemToOrderKey.put(it, (double) mapItemToINV.get(it));
                break;
            case RANDOM:
                List<Integer> shuffled = new ArrayList<Integer>(items);
                Collections.sort(shuffled);
                Collections.shuffle(shuffled, new Random(randomSeed));
                for (int r = 0; r < shuffled.size(); r++) mapItemToOrderKey.put(shuffled.get(r), (double) r);
                break;
        }
    }

    /**
     * Computes mae(X), the maximum average efficiency of any superset of X (Definition 4.5), by summing the
     * per-transaction bound over the elements of X's AE-List. The per-transaction bound has two non-zero
     * cases, chosen by comparing the maximum remaining utility mru with the average utility u/|X|.
     * (The paper's Definition 4.5 is the correct form; the pseudocode of its Algorithm 3 has a typo.)
     *
     * @param ul     the AE-List of itemset X
     * @param length |X|, the number of items in X
     * @return mae(X)
     */
    private double calMae(UtilityList ul, int length){
        // Step 1: start the sum of per-transaction bounds
        double sumMau = 0;
        // Step 2: add the bound of every transaction in which X occurs

        for(int i = 0;i<ul.elements.size();i++){
            Element element = ul.elements.get(i);
            double au = (double) element.iutils/length ;
            double tmae;
            double invNew = (double)(ul.inv + element.rMinInv);
            if (element.rmu != 0) {
                if(element.rmu > au) {
                    tmae = (double) (element.iutils  + element.rmu * element.nofitem) / ((length + element.nofitem)*invNew);
                }else {
                    tmae = (double) (element.iutils  + element.rmu) /((length + 1)*invNew);
                }
            }
            else {
                tmae = 0;
            }
            sumMau = sumMau + tmae;
        }
        return sumMau;
    }


    /**
     * Construct a utility list that contains all elements of X and Y (Algorithm 4 of the paper): its investment is
     * inv(Px) + inv(Py) - inv(P), and it has one element for every transaction containing both Px and Py.
     *
     * @param P  the AE-List of the common prefix P, or {@code null} when P is empty
     * @param px the AE-List of itemset Px = P + {x}
     * @param py the AE-List of itemset Py = P + {y}
     * @return the utility list of Pxy
     */
    private UtilityList construct(UtilityList P,UtilityList px,UtilityList py){
        // Step 1: compute inv(Pxy), subtracting the prefix investment that is counted twice
        UtilityList pxyUL = new UtilityList(py.item);
        long invOfItem = 0;
        if(P == null) {
            invOfItem = px.getInv() + py.getInv();
        }else{
            invOfItem = px.getInv() + py.getInv() - P.getInv();
        }
        pxyUL.addInvestion(invOfItem);

        // Step 2: for every transaction of Px that also contains Py, build the combined element
        // (utilities are added, and the prefix utility is subtracted so it is not counted twice)
        for(Element ex : px.elements){
            Element ey =  findElementWithTID(py, ex.tid);
            if (ey == null){
                continue;
            }
            if (P == null){
                Element eXY = new Element(ex.tid, ex.iutils + ey.iutils, ey.rmu, ey.rMinInv,ey.nofitem);
                pxyUL.addElement(eXY,py.getMn());
            }else{
                Element e = findElementWithTID(P, ex.tid);
                if (e != null) {
                    Element eXY = new Element(ex.tid, ex.iutils + ey.iutils - e.iutils, ey.rmu, ey.rMinInv,ey.nofitem);
                    pxyUL.addElement(eXY, py.getMn());
                }
            }
        }

        // Step 3: count this join and return the new list
        joinCount++;
        return pxyUL;
    }



    /**
     * This method is to find an element in a utility list with a given tid
     * @param ulist the utility list
     * @param tid the tid
     * @return the element
     */
    private Element findElementWithTID(UtilityList ulist, int tid){
        List<Element> list = ulist.elements;
        int first = 0;
        int last = list.size() - 1;
        while( first <= last ) {
            int middle = ( first + last ) >>> 1;
            if(list.get(middle).tid < tid){
                first = middle + 1;
            }
            else if(list.get(middle).tid > tid){
                last = middle - 1;
            }
            else{
                return list.get(middle);
            }
        }
        return null;
    }


    /**
     * Writes one high average-efficiency itemset to the output file and counts it.
     *
     * @param prefix the prefix items of the itemset
     * @param item   the last item of the itemset
     * @param ae     the average efficiency ae(X) of the itemset
     * @throws IOException if writing fails
     */
    private void writeOut(int[] prefix, int item, double ae) throws IOException {
        haeiCount++;
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < prefix.length; i++) {
            buffer.append(prefix[i]);
            buffer.append(' ');
        }
        buffer.append(item);
        buffer.append("#AE: ");
        buffer.append(ae);
        writer.write(buffer.toString());
        writer.newLine();
    }



    /** Reads the current heap usage (MB) and keeps the largest value seen in {@link #maxMemory}. */
    private void checkMemory() {
        double currentMemory = (Runtime.getRuntime().totalMemory() -  Runtime.getRuntime().freeMemory())/1024d/1024d;
        if (currentMemory > maxMemory) {
            maxMemory = currentMemory;
        }
    }


    /** Prints the threshold, the number of HAEIs, the running time and the memory figure to standard output. */
    public void printStats() {
        System.out.println("=============  HAEI-MINER ALGORITHM =============");
        System.out.println(" The final minae : " + min_AE);
        System.out.println(" High average-efficiency itemsets count : " + haeiCount);
        System.out.println(" Total time ~ " + (endTimestamp - startTimestamp) + " ms");
        System.out.println(" Memory ~ " + maxMemory + " MB");
        System.out.println(" Join count : " + joinCount);
        System.out.println("===================================================");
    }
}
