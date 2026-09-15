package com.tradesignal.service;

import com.tradesignal.model.AppState;
import com.tradesignal.model.Config;
import com.tradesignal.model.TradeLogEntry;
import com.tradesignal.store.DataStore;
import com.tradesignal.util.MarketHours;
import org.springframework.stereotype.Service;

import java.util.List;

/** Thin facade the controller talks to \u2014 the actual work lives in
 *  TradingScheduler (the 30s loop) and TradeExecutionService (open/close). */
@Service
public class TradingService {

    private final DataStore store;
    private final TradeExecutionService execution;
    private final TradingScheduler scheduler;

    public TradingService(DataStore store, TradeExecutionService execution, TradingScheduler scheduler) {
        this.store = store;
        this.execution = execution;
        this.scheduler = scheduler;
    }

    public boolean marketOpenNow() {
        return MarketHours.marketOpenNow();
    }

    public AppState getState() {
        return store.get();
    }

    public void updateConfig(Config incoming) {
        AppState state = store.get();
        Config cfg = state.config;
        if (incoming.symbol != null) cfg.symbol = incoming.symbol.trim();
        if (".NS".equals(incoming.suffix) || ".BO".equals(incoming.suffix)) cfg.suffix = incoming.suffix;
        if (incoming.investment >= 0) cfg.investment = incoming.investment;
        if ("intraday".equals(incoming.mode) || "longterm".equals(incoming.mode)) cfg.mode = incoming.mode;
        cfg.targetPct = incoming.targetPct;
        cfg.stopPct = incoming.stopPct;
        cfg.autoMode = incoming.autoMode;
        store.save();
        scheduler.tick();
    }

    public String startTrade() {
        AppState state = store.get();
        if (state.position != null) return "A position is already open.";
        if (state.config.symbol == null || state.config.symbol.isBlank()) return "Set a symbol first.";
        if (state.lastSignal == null || state.lastSignal.error != null) return "No live price yet \u2014 try again in a few seconds.";
        String symbol = execution.fullSymbol(state.config.symbol, state.config.suffix);
        boolean ok = execution.openPosition(symbol, state.lastSignal.price);
        return ok ? null : "Investment amount is too small to buy 1 share at this price.";
    }

    public String exitTrade() {
        AppState state = store.get();
        if (state.position == null) return "No open position.";
        double price = state.lastSignal != null && state.lastSignal.error == null ? state.lastSignal.price : state.position.entryPrice;
        execution.closePosition("Manual exit", price);
        return null;
    }

    public void reset() {
        store.reset();
    }

    public List<TradeLogEntry> getTradeLog() {
        return store.get().tradeLog;
    }
}
