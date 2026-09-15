package com.tradesignal.model;

import java.util.ArrayList;
import java.util.List;

public class AppState {
    public Config config = new Config();
    public Position position = null;
    public List<TradeLogEntry> tradeLog = new ArrayList<>();
    public SignalResult lastSignal = null;
    public String lastChecked = null;
}
