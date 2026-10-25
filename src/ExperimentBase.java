import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client base class shared by all experiment clients ({@link ExpPruning}, {@link ExpOrder}, {@link ExpTraversal},
 * {@link ExpScalability}, {@link ExpLazy}). An experiment client decides WHICH configurations to run (its axis of the ablation study);
 * this class does the common work: it parses the command line, runs every configuration in a FRESH JVM through the
 * {@link RunOne} client (so timings and memory never contaminate each other), enforces a time limit, and appends one
 * CSV row per run to {@code results/results.csv}. Runs already present in the CSV are skipped, so an interrupted
 * experiment can simply be started again.
 *
 * <p>Command line (all optional):
 * {@code --mode smoke|quick|full  --reps N  --timeout SECONDS  --heap MB  --datasets chess mushroom ...
 * --out DIR  --root DIR}</p>
 *
 * <p>The server classes that do the actual mining are {@link AlgoHAEI} (the authors' MAEI plus ablation switches) and
 * {@link AlgoHAEIMiner} (the two-phase baseline).</p>
 */
public abstract class ExperimentBase {

    /** A benchmark dataset and the thresholds used for it in each mode. */
    protected static final class Dataset {
        /** Short name, e.g. "chess". */
        final String name;
        /** Path of the database file, relative to the project root. */
        final String dataPath;
        /** Path of the stock (investment) file, relative to the project root. */
        final String stockPath;
        /** minae values of the full mode. */
        final double[] full;
        /** minae values of the quick mode. */
        final double[] quick;
        /** minae values of the smoke mode. */
        final double[] smoke;
        /** The "middle" minae used by the order, traversal and scalability experiments. */
        final double mid;
        /** Maximum repetitions for this dataset (0 = no cap); used for the slow Accidents runs. */
        final int repsCap;

        /**
         * Creates a dataset description.
         *
         * @param name      short name
         * @param dataPath  database file path (relative to the project root)
         * @param stockPath stock file path (relative to the project root)
         * @param full      thresholds of the full mode
         * @param quick     thresholds of the quick mode
         * @param smoke     thresholds of the smoke mode
         * @param mid       middle threshold of the full mode
         * @param repsCap   cap on repetitions, 0 for none
         */
        Dataset(String name, String dataPath, String stockPath, double[] full, double[] quick, double[] smoke,
                double mid, int repsCap) {
            this.name = name; this.dataPath = dataPath; this.stockPath = stockPath;
            this.full = full; this.quick = quick; this.smoke = smoke; this.mid = mid; this.repsCap = repsCap;
        }

        /**
         * Returns the threshold list of a mode.
         *
         * @param mode "smoke", "quick" or "full"
         * @return the minae values for that mode
         */
        double[] thresholds(String mode) {
            return mode.equals("full") ? full : mode.equals("quick") ? quick : smoke;
        }
    }

    /** One configuration (one row of the results table before repetition). */
    protected static final class Config {
        /** Experiment axis: pruning, order, traversal, scalability or lazy. */
        final String axis;
        /** Dataset name. */
        final String dataset;
        /** The minimum average-efficiency threshold. */
        final double minae;
        /** Variant name, a key of {@link #VARIANTS}. */
        final String variant;
        /** Item order name (AEUB_ASC, AEUB_DESC, INVESTMENT_ASC, RANDOM). */
        final String order;
        /** Search order name (DFS or BFS). */
        final String traversal;
        /** Fraction of the transactions used (1.0 = all). */
        final double frac;

        /**
         * Creates a configuration.
         *
         * @param axis      experiment axis
         * @param dataset   dataset name
         * @param minae     threshold
         * @param variant   variant name
         * @param order     item order name
         * @param traversal DFS or BFS
         * @param frac      fraction of transactions
         */
        Config(String axis, String dataset, double minae, String variant, String order, String traversal, double frac) {
            this.axis = axis; this.dataset = dataset; this.minae = minae; this.variant = variant;
            this.order = order; this.traversal = traversal; this.frac = frac;
        }
    }

    /** The benchmark datasets in the order they are run. */
    protected static final Map<String, Dataset> DATASETS = new LinkedHashMap<String, Dataset>();
    /** Variant name to the {@link RunOne} options that select it. */
    protected static final Map<String, String[]> VARIANTS = new LinkedHashMap<String, String[]>();
    /** The four item orders of the ordering experiment. */
    protected static final String[] ORDERS = {"AEUB_ASC", "AEUB_DESC", "INVESTMENT_ASC", "RANDOM"};
    /** Fractions of the transactions used by the scalability experiment. */
    protected static final double[] SCAL_FRACTIONS = {0.2, 0.4, 0.6, 0.8, 1.0};
    /** Variants of the pruning experiment (the lazy-EAECS variant has its own experiment, {@link ExpLazy}). */
    protected static final String[] PRUNING_VARIANTS = {"FULL", "NO_EAECP", "NO_MAEP", "NEITHER", "HAEIMiner_mem", "HAEIMiner_disk"};
    /** Variants of the lazy-EAECS experiment: EAECS built in the scan, built on demand, and not used at all. */
    protected static final String[] LAZY_VARIANTS = {"FULL", "FULL_LAZY", "NO_EAECP"};
    /** Variants compared in the scalability experiment. */
    protected static final String[] SCAL_VARIANTS = {"FULL", "NEITHER", "HAEIMiner_mem", "HAEIMiner_disk"};
    /** Column names of the results CSV. */
    protected static final String[] CSV_FIELDS = {"axis", "dataset", "minae", "variant", "order", "traversal", "frac",
        "rep", "status", "runtime_ms", "peak_heap_mb", "baseline_heap_mb", "join_count", "haei_count", "candidates",
        "ae_min", "ae_max", "ae_mean", "ae_sum", "eff_max", "eff_mean", "size_mean", "size_max", "itemset_hash", "eaecs_ms"};

    static {
        DATASETS.put("chess", new Dataset("chess", "data/chess_DBUtility.txt", "data/chess_stock.txt",
                new double[]{50, 20, 10, 5}, new double[]{20, 10}, new double[]{50}, 20, 0));
        DATASETS.put("mushroom", new Dataset("mushroom", "data/mushroom_DBUtility.txt", "data/mushroom_stock.txt",
                new double[]{10, 5, 3, 2, 1}, new double[]{5, 3}, new double[]{10}, 5, 0));
        DATASETS.put("foodmart", new Dataset("foodmart", "data/foodmart_DBUtility.txt", "data/foodmart_stock.txt",
                new double[]{5, 2, 1, 0.5, 0.2}, new double[]{2, 1}, new double[]{5}, 2, 0));
        DATASETS.put("accidents", new Dataset("accidents", "data/accidents_DBUtility.txt", "data/accidents_stock.txt",
                new double[]{800, 400, 200, 100}, new double[]{}, new double[]{}, 400, 1));
        VARIANTS.put("FULL", new String[]{"-algo", "MAEI", "-maep", "on", "-eaecp", "on"});
        VARIANTS.put("NO_EAECP", new String[]{"-algo", "MAEI", "-maep", "on", "-eaecp", "off"});
        VARIANTS.put("NO_MAEP", new String[]{"-algo", "MAEI", "-maep", "off", "-eaecp", "on"});
        VARIANTS.put("NEITHER", new String[]{"-algo", "MAEI", "-maep", "off", "-eaecp", "off"});
        VARIANTS.put("FULL_LAZY", new String[]{"-algo", "MAEI", "-maep", "on", "-eaecp", "on", "-lazy", "on"});
        VARIANTS.put("HAEIMiner_mem", new String[]{"-algo", "HAEIMiner", "-disk", "off"});
        VARIANTS.put("HAEIMiner_disk", new String[]{"-algo", "HAEIMiner", "-disk", "on"});
    }

    /** Run mode: smoke, quick or full. */
    protected String mode = "quick";
    /** Repetitions per configuration (set from the mode unless given on the command line). */
    protected int reps;
    /** Time limit per run in seconds (set from the mode unless given on the command line). */
    protected int timeoutSec;
    /** JVM heap (MB, -Xmx) given to every run, the same for all runs. */
    protected int heapMb = 4096;
    /** Datasets requested on the command line; empty means all datasets that have thresholds in the mode. */
    protected List<String> datasetFilter = new ArrayList<String>();
    /** Project root; data paths are resolved against it. */
    protected Path root = Paths.get(".").toAbsolutePath().normalize();
    /** Output directory (results.csv is written here). */
    protected Path outDir;

    /**
     * Name of the experiment axis written to the CSV column "axis".
     *
     * @return pruning, order, traversal or scalability
     */
    protected abstract String axisName();

    /**
     * Lists the configurations of this experiment for one dataset.
     *
     * @param d   the dataset
     * @param mid the middle threshold to use for experiments that run at a single threshold
     * @return the configurations to run, in order
     */
    protected abstract List<Config> buildConfigs(Dataset d, double mid);

    /**
     * Name of the CSV file this experiment appends to (inside the output directory).
     *
     * @return "results.csv" unless an experiment keeps its own file
     */
    protected String csvName() {
        return "results.csv";
    }

    /**
     * Number of repetitions that overrides the mode default and the dataset cap; 0 means "no override".
     *
     * @return the forced repetition count, or 0
     */
    protected int forcedReps() {
        return 0;
    }

    /**
     * Whether this experiment only runs in full mode (the scalability experiment does).
     *
     * @return true if the experiment is skipped in smoke and quick mode
     */
    protected boolean fullModeOnly() {
        return false;
    }

    /**
     * Runs the experiment: parses the arguments, builds the configurations, runs each one in a fresh JVM and appends
     * the results to the CSV.
     *
     * @param args the command line (see the class description)
     * @throws Exception if a file cannot be written or a process cannot be started
     */
    public final void execute(String[] args) throws Exception {
        // Step 1: read the command line and fill in the mode defaults
        parseArgs(args);
        if (reps == 0) reps = mode.equals("full") ? 3 : mode.equals("quick") ? 2 : 1;
        if (timeoutSec == 0) timeoutSec = mode.equals("full") ? 900 : mode.equals("quick") ? 120 : 60;
        if (outDir == null) outDir = root.resolve("results");
        Files.createDirectories(outDir);
        if (fullModeOnly() && !mode.equals("full")) {
            System.out.println(axisName() + ": runs only in --mode full, skipped.");
            return;
        }
        writeSystemInfoIfMissing();

        // Step 2: build the list of configurations for every requested dataset
        List<Config> configs = new ArrayList<Config>();
        List<String> names = datasetFilter.isEmpty() ? new ArrayList<String>(DATASETS.keySet()) : datasetFilter;
        for (String n : names) {
            Dataset d = DATASETS.get(n);
            if (d == null) { System.out.println("Unknown dataset " + n); continue; }
            double[] thr = d.thresholds(mode);
            if (thr.length == 0) continue;
            if (!Files.exists(root.resolve(d.dataPath))) { System.out.println("SKIP " + n + ": " + d.dataPath + " not found"); continue; }
            double mid = d.mid;
            if (!mode.equals("full") && !contains(thr, d.mid)) mid = thr[0];
            configs.addAll(buildConfigs(d, mid));
        }

        // Step 3: skip configurations that are already in the CSV (resume support)
        Path csv = outDir.resolve(csvName());
        Set<String> done = readDoneKeys(csv);
        boolean isNew = !Files.exists(csv);
        if (!isNew) migrateOldCsv(csv);
        if (isNew) Files.write(csv, (String.join(",", CSV_FIELDS) + "\n").getBytes(StandardCharsets.UTF_8));
        List<Config> todo = new ArrayList<Config>();
        for (Config c : configs) if (!done.contains(key(c))) todo.add(c);
        System.out.println(axisName() + ": " + configs.size() + " configurations, " + todo.size() + " to run (reps=" + (forcedReps() > 0 ? forcedReps() : reps)
                + ", timeout=" + timeoutSec + "s, heap=" + heapMb + "MB)");

        // Step 4: run every configuration (each repetition in its own JVM) and append the rows
        long t0 = System.currentTimeMillis();
        int n = 0;
        for (Config c : todo) {
            n++;
            Dataset d = DATASETS.get(c.dataset);
            int r = forcedReps() > 0 ? forcedReps() : (d.repsCap > 0 ? Math.min(reps, d.repsCap) : reps);
            for (int rep = 0; rep < r; rep++) {
                String[] row = runIsolated(c, d, rep);
                Files.write(csv, (String.join(",", row) + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
                System.out.println(String.format(Locale.ROOT, "[%d/%d %.0fs] %s %s minae=%s %s %s %s frac=%s rep=%d -> %s %s",
                        n, todo.size(), (System.currentTimeMillis() - t0) / 1000.0, c.axis, c.dataset, fmt(c.minae), c.variant,
                        c.order, c.traversal, Double.toString(c.frac), rep, row[8], row[9]));
                if (!row[8].equals("ok")) break;   // do not repeat a timeout or an error
            }
        }
        System.out.println(axisName() + " DONE. Results in " + csv);
    }

    /**
     * Runs one repetition of one configuration in a fresh JVM (the {@link RunOne} client) and returns the CSV row.
     *
     * @param c   the configuration
     * @param d   its dataset
     * @param rep the repetition number
     * @return the CSV fields in the order of {@link #CSV_FIELDS}
     * @throws IOException if the process cannot be started
     */
    private String[] runIsolated(Config c, Dataset d, int rep) throws IOException {
        // Step 1: build the command line of the child JVM
        String javaBin = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        List<String> cmd = new ArrayList<String>(Arrays.asList(javaBin, "-Xmx" + heapMb + "m", "-XX:+UseSerialGC", "-Xss256m",
                "-cp", System.getProperty("java.class.path"), "RunOne",
                "-data", root.resolve(d.dataPath).toString(), "-stock", root.resolve(d.stockPath).toString(),
                "-minae", fmt(c.minae), "-order", c.order, "-traversal", c.traversal, "-frac", Double.toString(c.frac)));
        cmd.addAll(Arrays.asList(VARIANTS.get(c.variant)));
        File outFile = File.createTempFile("exp_out", ".txt");
        File errFile = File.createTempFile("exp_err", ".txt");
        outFile.deleteOnExit(); errFile.deleteOnExit();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(outFile);
        pb.redirectError(errFile);

        // Step 2: run it with a time limit
        String status = "ok";
        String json = "";
        try {
            Process p = pb.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor();
                status = "TIMEOUT";
            } else if (p.exitValue() != 0) {
                List<String> err = Files.readAllLines(errFile.toPath(), StandardCharsets.UTF_8);
                status = "ERROR: " + (err.isEmpty() ? "?" : err.get(err.size() - 1)).replace(',', ';');
                if (status.length() > 100) status = status.substring(0, 100);
            } else {
                List<String> out = Files.readAllLines(outFile.toPath(), StandardCharsets.UTF_8);
                json = out.isEmpty() ? "" : out.get(out.size() - 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "ERROR: interrupted";
        }

        // Step 3: turn the JSON line into the CSV fields
        String[] row = new String[CSV_FIELDS.length];
        row[0] = c.axis; row[1] = c.dataset; row[2] = fmt(c.minae); row[3] = c.variant; row[4] = c.order;
        row[5] = c.traversal; row[6] = Double.toString(c.frac); row[7] = Integer.toString(rep); row[8] = status;
        String[] keys = {"runtime_ms", "peak_heap_mb", "baseline_heap_mb", "join_count", "haei_count", "candidates",
            "ae_min", "ae_max", "ae_mean", "ae_sum", "eff_max", "eff_mean", "size_mean", "size_max", "itemset_hash", "eaecs_ms"};
        for (int i = 0; i < keys.length; i++) row[9 + i] = status.equals("ok") ? jsonValue(json, keys[i]) : "";
        return row;
    }

    /**
     * Upgrades a results file written by an earlier version (fewer columns) by padding every row with empty values for the
     * new columns, so that new rows can be appended without misaligning the table. Files that are already current are left alone.
     *
     * @param csv the existing results file
     * @throws IOException if the file cannot be read or rewritten
     */
    private static void migrateOldCsv(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return;
        int have = lines.get(0).split(",", -1).length;
        if (have >= CSV_FIELDS.length) return;
        StringBuilder pad = new StringBuilder();
        for (int i = have; i < CSV_FIELDS.length; i++) pad.append(',');
        List<String> out = new ArrayList<String>();
        out.add(String.join(",", CSV_FIELDS));
        for (int i = 1; i < lines.size(); i++) out.add(lines.get(i) + pad);
        Files.write(csv, out, StandardCharsets.UTF_8);
        System.out.println("Upgraded " + csv.getFileName() + " to the new column layout (" + have + " -> " + CSV_FIELDS.length + " columns).");
    }

    /**
     * Extracts a numeric field from the one-line JSON printed by {@link RunOne}.
     *
     * @param json the JSON text
     * @param key  the field name
     * @return the value as text, or an empty string if the field is missing
     */
    private static String jsonValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\\s*(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Formats a threshold the way it is written to the CSV: integers without a decimal point (5), others as they are (0.5).
     *
     * @param d the value
     * @return the text form
     */
    protected static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    /**
     * Builds the identity of a configuration, used for resuming.
     *
     * @param c the configuration
     * @return a string that is equal for equal configurations
     */
    private static String key(Config c) {
        return String.join("|", c.axis, c.dataset, fmt(c.minae), c.variant, c.order, c.traversal, Double.toString(c.frac));
    }

    /**
     * Reads the configurations already present in the results CSV.
     *
     * @param csv the CSV path (may not exist yet)
     * @return the set of configuration keys already recorded
     * @throws IOException if the file cannot be read
     */
    private static Set<String> readDoneKeys(Path csv) throws IOException {
        Set<String> done = new HashSet<String>();
        if (!Files.exists(csv)) return done;
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",", -1);
            if (f.length >= 7) done.add(String.join("|", f[0], f[1], f[2], f[3], f[4], f[5], f[6]));
        }
        return done;
    }

    /**
     * Checks whether a value occurs in an array.
     *
     * @param a the array
     * @param v the value
     * @return true if {@code v} is in {@code a}
     */
    private static boolean contains(double[] a, double v) {
        for (double x : a) if (x == v) return true;
        return false;
    }

    /**
     * Parses the command line into the protected fields.
     *
     * @param args the command line
     */
    private void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode": mode = args[++i]; break;
                case "--reps": reps = Integer.parseInt(args[++i]); break;
                case "--timeout": timeoutSec = Integer.parseInt(args[++i]); break;
                case "--heap": heapMb = Integer.parseInt(args[++i]); break;
                case "--out": outDir = Paths.get(args[++i]).toAbsolutePath().normalize(); break;
                case "--root": root = Paths.get(args[++i]).toAbsolutePath().normalize(); break;
                case "--datasets":
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) datasetFilter.add(args[++i]);
                    break;
                default: System.out.println("Ignoring unknown option " + args[i]);
            }
        }
    }

    /**
     * Writes a short system description to {@code system_info.txt} unless the file already exists (the Python launcher
     * writes a fuller one that also contains the CPU name).
     *
     * @throws IOException if the file cannot be written
     */
    private void writeSystemInfoIfMissing() throws IOException {
        Path f = outDir.resolve("system_info.txt");
        if (Files.exists(f)) return;
        long ram = -1;
        try {
            ram = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                    .getTotalMemorySize() / (1024 * 1024);
        } catch (Throwable ignored) {
            // RAM size is optional information
        }
        List<String> lines = new ArrayList<String>();
        lines.add("MAEI ablation benchmark - system information");
        lines.add("mode: " + mode + "   reps: " + reps + "   timeout: " + timeoutSec + "s   heap: " + heapMb + " MB");
        lines.add("os: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        lines.add("logical cpus: " + Runtime.getRuntime().availableProcessors());
        lines.add("ram (MB): " + (ram < 0 ? "unknown" : Long.toString(ram)));
        lines.add("java: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        Files.write(f, lines, StandardCharsets.UTF_8);
    }
}
