package com.tradesignal.model;

public class TradeLogEntry {
    public String symbol;
    public String mode;
    public double entryPrice;
    public int qty;
    /** Money actually put to work on this order: entryPrice * qty. */
    public double investedAmount;
    public String entryTime;
    public double exitPrice;
    public String exitTime;
    public double pnl;
    public double pnlPct;
    public String reason;
    public String sizingNote;
}
