package com.tradesignal.model;

public class Config {
    public String symbol = "";
    public String suffix = ".NS";
    public double investment = 1000000; // 10 lakh default
    public String mode = "intraday"; // intraday | longterm
    public double targetPct = 2;
    public double stopPct = 1;
    /** "percent" or "rupees" — decides whether targetPct/stopPct or targetRupees/stopRupees are used. */
    public String exitMode = "rupees";
    /** Absolute rupee move from entry price at which to book profit (used when exitMode = rupees). */
    public double targetRupees = 2;
    /** Absolute rupee move from entry price at which to cut the loss (used when exitMode = rupees). */
    public double stopRupees = 1;
    /** Safety cap: maximum number of trades the engine may open per calendar day (IST). Null = no cap, decide purely from market conditions. */
    public Integer maxOrdersPerDay = null;
    public boolean autoMode = true;
    /** % of total capital you're willing to lose if the stop-loss hits — drives position size dynamically. */
    public double riskPerTradePct = 1;
    /** Hard safety ceiling: never put more than this % of investment into one order, no matter what the sizing math suggests. */
    public double maxOrderPct = 50;
    /** Max number of buy tranches (averaging in) allowed on one open position, so "buy more if price drops" can't run away unbounded. */
    public int maxLegsPerPosition = 3;
}
