package com.tradesignal.model;

public class Position {
    public String symbol;
    public String mode;
    public double entryPrice;
    public int qty;
    /** Money Actually put to work on this order: entryPrice * qty. */
    public double investedAmount;
    public String entryTime; // ISO-8601
    public double target;
    public double stopLoss;
    /** Human-readable explanation of how qty was chosen (risk %, volatility, any caps applied). */
    public String sizingNote;
}
