package com.tradesignal.model;

import java.util.ArrayList;
import java.util.List;

public class Position {
    public String symbol;
    public String mode;
    /** Weighted-average entry price across all legs (just the first leg's price if only one buy has happened). */
    public double entryPrice;
    /** Total shares held across all legs. */
    public int qty;
    /** Total money put to work across all legs: sum(leg.price * leg.qty). */
    public double investedAmount;
    /** Time of the FIRST buy into this position. */
    public String entryTime;
    /** Target/stop-loss, recalculated from the weighted-average entry price whenever a leg is added. */
    public double target;
    public double stopLoss;
    /** Human-readable explanation of how qty/entry was chosen (risk %, volatility, averaging, any caps applied). */
    public String sizingNote;
    /** Every individual buy that has gone into this position, in order. */
    public List<PositionLeg> legs = new ArrayList<>();

    public static class PositionLeg {
        public double price;
        public int qty;
        public String time;
    }
}
