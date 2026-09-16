package com.tradesignal.model;

import java.util.ArrayList;
import java.util.List;

public class AppState {
    public Config config = new Config();
    public Position position = null;
    public List<TradeLogEntry> tradeLog = new ArrayList<>();
    public SignalResult lastSignal = null;
    public String lastChecked = null;
    /** Latest ATR volatility (% of price), used for risk-based position sizing. */
    public Double lastVolatilityPct = null;
    /** Plain-English read on what's likely to happen next (distance to target/stop, or why no trade is open). */
    public String nextMoveHint = null;
}
