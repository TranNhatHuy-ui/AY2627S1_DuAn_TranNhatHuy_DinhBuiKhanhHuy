import java.util.ArrayList;
import java.util.List;

/**
 * Client class of experiment 2: the effect of the item order. At the middle threshold of each dataset it runs the full
 * algorithm with the paper's order (aeub ascending), aeub descending, investment ascending (the paper's MAEI-v2) and a
 * fixed-seed random order used as a negative control. Server class: {@link AlgoHAEI}.
 */
public class ExpOrder extends ExperimentBase {
    /**
     * Entry point.
     *
     * @param args see {@link ExperimentBase}
     * @throws Exception if the experiment fails
     */
    public static void main(String[] args) throws Exception {
        new ExpOrder().execute(args);
    }

    /**
     * Names the axis of this experiment in the results CSV.
     *
     * @return "order"
     */
    @Override
    protected String axisName() {
        return "order";
    }

    /**
     * Builds the configurations of this experiment for one dataset: the four item orders, run with the full algorithm at the middle threshold.
     *
     * @param d   the dataset
     * @param mid the middle threshold of the dataset
     * @return the configurations to run
     */
    @Override
    protected List<Config> buildConfigs(Dataset d, double mid) {
        List<Config> out = new ArrayList<Config>();
        for (String order : ORDERS) {
            out.add(new Config("order", d.name, mid, "FULL", order, "DFS", 1.0));
        }
        return out;
    }
}
