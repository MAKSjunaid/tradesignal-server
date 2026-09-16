package com.tradesignal.model;

public class Config {
    public String symbol = "";
    public String suffix = ".NS";
    public double investment = 10000;
    public String mode = "intraday"; // intraday | longterm
    public double targetPct = 2;
    public double stopPct = 1;
    public boolean autoMode = false;
    /** % of total capital you're willing to lose if the stop-loss hits \u2014 drives position size dynamically. */
    public double riskPerTradePct = 1;
    /** Hard safety Ceiling: never put more than this % of investment into one order, no matter what the sizing math suggests. */
    public double maxOrderPct = 50;
}
