/*
 * @author Xuanwei Zhang
 * @date 2025/12/026
 * @description: MAEI Algorithm
 * */
/**
 * One element of an AE-List (Definition 4.4): the information stored for one itemset in one transaction.
 */
class Element {
    /** Identifier (position) of the transaction. */
    final int tid;
    /** Utility u(X, T) of the itemset in this transaction. */
    final int iutils;
    /** mru: maximum utility among the items that come after the itemset in this transaction (Definition 4.1). */
    final int rmu;
    /** minrinv: minimum investment among the items that come after the itemset in this transaction (Definition 4.3). */
    final long rMinInv;
    /** rn: number of items that come after the itemset in this transaction (Definition 4.2). */
    final int nofitem;

    /**
     * Creates an element.
     *
     * @param tid     transaction identifier
     * @param iutils  utility of the itemset in the transaction
     * @param rmu     maximum remaining utility
     * @param rMinInv minimum remaining investment
     * @param nofitem number of remaining items
     */
    public Element(int tid, int iutils, int rmu,long rMinInv,int nofitem){
        this.tid = tid;
        this.iutils = iutils;
        this.rmu = rmu;
        this.rMinInv = rMinInv;
        this.nofitem = nofitem;
    }
}
