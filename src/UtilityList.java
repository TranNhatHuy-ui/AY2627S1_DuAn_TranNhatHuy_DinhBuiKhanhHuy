/*
 * @author Xuanwei Zhang
 * @date 2025/12/026
 * @description: MAEI Algorithm
 * */
import java.util.ArrayList;
import java.util.List;

/**
 * The Average-Efficiency List (AE-List, Definition 4.4) of one itemset: its total investment, the running sum of
 * u / inv over its transactions, and one {@link Element} per transaction that contains the itemset.
 */
public class UtilityList {
    /** The last item of the itemset this list belongs to. */
    int item;
    /** Largest number of remaining items (rn) over all elements; 0 means the itemset cannot be extended. */
    int mn = 0;
    /** inv(X): total investment of the itemset. */
    long inv = 0;
    /** Sum over the elements of u / inv(X); dividing by |X| gives ae(X) (Definition 3.5). */
    double Sumae = 0;


    /** The elements (one per transaction containing the itemset), kept in increasing tid order. */
    List<Element> elements = new ArrayList<Element>();

    /**
     * Creates an empty AE-List.
     *
     * @param item the last item of the itemset
     */
    public UtilityList(int item) {this.item = item;}

    /**
     * Sets the total investment inv(X) of the itemset.
     *
     * @param invOfItem the investment value
     */
    public void addInvestion(long invOfItem){
        inv = invOfItem;
    }

    /**
     * Appends an element, adds its u / inv to {@link #Sumae}, and updates {@link #mn}.
     *
     * @param element the element to append (its tid must be larger than those already in the list)
     * @param n       the number of remaining items of this element
     */
    public void addElement(Element element, int n) {
        Sumae += (double) element.iutils / inv;
        elements.add(element);
        if(mn < n) {
            mn = n;
        }
    }

    /**
     * Returns the largest number of remaining items over the elements.
     *
     * @return {@link #mn}
     */
    public int getMn(){return mn;}


    /**
     * Returns the total investment of the itemset.
     *
     * @return inv(X)
     */
    public long getInv(){
        return inv;
    }
}
