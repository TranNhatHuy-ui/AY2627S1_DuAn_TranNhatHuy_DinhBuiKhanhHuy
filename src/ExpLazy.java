import java.util.ArrayList;
import java.util.List;

/**
 * Client class of the lazy-EAECS experiment. EAECS (the co-occurrence structure of Pruning Strategy 3) is normally built for every pair
 * of items during the second database scan. Here the same algorithm is run three ways at every threshold: {@code FULL} (EAECS built in
 * the scan, the authors' way), {@code FULL_LAZY} (EAECS(x,y) computed only when the search first asks for the pair, then cached) and
 * {@code NO_EAECP} (no EAECS at all). All three must find the same itemsets; {@code FULL} and {@code FULL_LAZY} must also make exactly
 * the same number of joins, because both give the same EAECS values. What differs is the time and memory spent on the structure; the
 * column {@code eaecs_ms} of the results isolates that time. Server class: {@link AlgoHAEI}.
 */
public class ExpLazy extends ExperimentBase {
    /**
     * Entry point.
     *
     * @param args see {@link ExperimentBase}
     * @throws Exception if the experiment fails
     */
    public static void main(String[] args) throws Exception {
        new ExpLazy().execute(args);
    }

    /**
     * Names the axis of this experiment in the results CSV.
     *
     * @return "lazy"
     */
    @Override
    protected String axisName() {
        return "lazy";
    }

    /**
     * Builds the configurations for one dataset: every threshold of the dataset crossed with FULL, FULL_LAZY and NO_EAECP.
     *
     * @param d   the dataset
     * @param mid the middle threshold of the dataset (not used; all thresholds are run)
     * @return the configurations to run
     */
    @Override
    protected List<Config> buildConfigs(Dataset d, double mid) {
        List<Config> out = new ArrayList<Config>();
        for (double minae : d.thresholds(mode)) {
            for (String variant : LAZY_VARIANTS) {
                out.add(new Config("lazy", d.name, minae, variant, "AEUB_ASC", "DFS", 1.0));
            }
        }
        return out;
    }
}
