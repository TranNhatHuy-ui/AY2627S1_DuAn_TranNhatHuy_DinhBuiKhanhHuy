import java.util.ArrayList;
import java.util.List;

/**
 * Client class of experiment 5: comparison of the efficiency values of the itemsets found. Every variant must find the
 * same itemsets with the same average efficiency ae(X); this experiment records, for each variant, the minimum, mean,
 * maximum and sum of ae(X), the efficiency e(X) = ae(X) * |X|, the itemset sizes and a fingerprint of the set of itemsets
 * (see {@link RunOne}), so that python/analyze.py can check the agreement and describe the values.
 *
 * <p>Timing is irrelevant here, so every configuration runs only once and the results go to their own file,
 * {@code results/efficiency.csv}, leaving {@code results.csv} untouched. It is not part of the default launcher run; start it with
 * {@code python python/run_all.py --mode quick --axes efficiency} or {@code java -cp out ExpEfficiency --mode quick}.
 * Server classes: {@link AlgoHAEI} and {@link AlgoHAEIMiner}.</p>
 */
public class ExpEfficiency extends ExperimentBase {
    /**
     * Entry point.
     *
     * @param args see {@link ExperimentBase}
     * @throws Exception if the experiment fails
     */
    public static void main(String[] args) throws Exception {
        new ExpEfficiency().execute(args);
    }

    /**
     * Names the axis of this experiment in the results CSV.
     *
     * @return "efficiency"
     */
    @Override
    protected String axisName() {
        return "efficiency";
    }

    /**
     * Keeps this experiment's rows in their own file.
     *
     * @return "efficiency.csv"
     */
    @Override
    protected String csvName() {
        return "efficiency.csv";
    }

    /**
     * One run per configuration is enough because only the values found are compared, not the timings.
     *
     * @return 1
     */
    @Override
    protected int forcedReps() {
        return 1;
    }

    /**
     * Builds the configurations for one dataset: every pruning variant and both baselines at every threshold, plus the three
     * non-default item orders and breadth-first search at the middle threshold.
     *
     * @param d   the dataset
     * @param mid the middle threshold of the dataset
     * @return the configurations to run
     */
    @Override
    protected List<Config> buildConfigs(Dataset d, double mid) {
        List<Config> out = new ArrayList<Config>();
        for (double minae : d.thresholds(mode)) {
            for (String variant : VARIANTS.keySet()) {
                out.add(new Config("efficiency", d.name, minae, variant, "AEUB_ASC", "DFS", 1.0));
            }
        }
        for (String order : ORDERS) {
            if (!order.equals("AEUB_ASC")) out.add(new Config("efficiency", d.name, mid, "FULL", order, "DFS", 1.0));
        }
        out.add(new Config("efficiency", d.name, mid, "FULL", "AEUB_ASC", "BFS", 1.0));
        return out;
    }
}
