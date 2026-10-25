import java.util.ArrayList;
import java.util.List;

/**
 * Client class of experiment 1: the contribution of each pruning strategy, and the comparison with the baseline.
 * For every dataset and every threshold it runs FULL (MAEP + EAECP), NO_EAECP, NO_MAEP, NEITHER, and the two HAEIMiner
 * baselines (database in memory, and re-read from disk on every scan). All variants must find the same itemsets; the
 * differences are in runtime, memory and join calls. Server classes: {@link AlgoHAEI} and {@link AlgoHAEIMiner}.
 */
public class ExpPruning extends ExperimentBase {
    /**
     * Entry point.
     *
     * @param args see {@link ExperimentBase}
     * @throws Exception if the experiment fails
     */
    public static void main(String[] args) throws Exception {
        new ExpPruning().execute(args);
    }

    /**
     * Names the axis of this experiment in the results CSV.
     *
     * @return "pruning"
     */
    @Override
    protected String axisName() {
        return "pruning";
    }

    /**
     * Builds the configurations of this experiment for one dataset: every threshold of the dataset crossed with every variant (FULL, NO_EAECP, NO_MAEP, NEITHER, HAEIMiner_mem, HAEIMiner_disk).
     *
     * @param d   the dataset
     * @param mid the middle threshold of the dataset
     * @return the configurations to run
     */
    @Override
    protected List<Config> buildConfigs(Dataset d, double mid) {
        List<Config> out = new ArrayList<Config>();
        for (double minae : d.thresholds(mode)) {
            for (String variant : PRUNING_VARIANTS) {
                out.add(new Config("pruning", d.name, minae, variant, "AEUB_ASC", "DFS", 1.0));
            }
        }
        return out;
    }
}
