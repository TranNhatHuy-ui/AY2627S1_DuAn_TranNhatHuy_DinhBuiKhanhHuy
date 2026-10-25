import java.util.ArrayList;
import java.util.List;

/**
 * Client class of experiment 3: depth-first (the paper's) versus breadth-first search of the itemset enumeration tree,
 * run with the full algorithm at the middle threshold of each dataset. Both must visit the same nodes (same joins and
 * results); the difference to look for is memory. Server class: {@link AlgoHAEI}.
 */
public class ExpTraversal extends ExperimentBase {
    /**
     * Entry point.
     *
     * @param args see {@link ExperimentBase}
     * @throws Exception if the experiment fails
     */
    public static void main(String[] args) throws Exception {
        new ExpTraversal().execute(args);
    }

    /**
     * Names the axis of this experiment in the results CSV.
     *
     * @return "traversal"
     */
    @Override
    protected String axisName() {
        return "traversal";
    }

    /**
     * Builds the configurations of this experiment for one dataset: DFS and BFS, run with the full algorithm at the middle threshold.
     *
     * @param d   the dataset
     * @param mid the middle threshold of the dataset
     * @return the configurations to run
     */
    @Override
    protected List<Config> buildConfigs(Dataset d, double mid) {
        List<Config> out = new ArrayList<Config>();
        out.add(new Config("traversal", d.name, mid, "FULL", "AEUB_ASC", "DFS", 1.0));
        out.add(new Config("traversal", d.name, mid, "FULL", "AEUB_ASC", "BFS", 1.0));
        return out;
    }
}
