import java.util.ArrayList;
import java.util.List;

/**
 * Client class of experiment 4: scalability with the number of transactions. At the middle threshold of each dataset it
 * runs FULL, NEITHER and both HAEIMiner baselines on the first 20%, 40%, 60%, 80% and 100% of the transactions. Note
 * that the threshold is fixed, so the number of itemsets found also grows with the data. Runs in full mode only.
 * Server classes: {@link AlgoHAEI} and {@link AlgoHAEIMiner}.
 */
public class ExpScalability extends ExperimentBase {
    /**
     * Entry point.
     *
     * @param args see {@link ExperimentBase}
     * @throws Exception if the experiment fails
     */
    public static void main(String[] args) throws Exception {
        new ExpScalability().execute(args);
    }

    /**
     * Names the axis of this experiment in the results CSV.
     *
     * @return "scalability"
     */
    @Override
    protected String axisName() {
        return "scalability";
    }

    /**
     * Tells the base class that this experiment is too long for smoke and quick mode.
     *
     * @return true
     */
    @Override
    protected boolean fullModeOnly() {
        return true;
    }

    /**
     * Builds the configurations of this experiment for one dataset: every fraction of the data crossed with FULL, NEITHER, HAEIMiner_mem and HAEIMiner_disk, at the middle threshold.
     *
     * @param d   the dataset
     * @param mid the middle threshold of the dataset
     * @return the configurations to run
     */
    @Override
    protected List<Config> buildConfigs(Dataset d, double mid) {
        List<Config> out = new ArrayList<Config>();
        for (double frac : SCAL_FRACTIONS) {
            for (String variant : SCAL_VARIANTS) {
                out.add(new Config("scalability", d.name, mid, variant, "AEUB_ASC", "DFS", frac));
            }
        }
        return out;
    }
}
