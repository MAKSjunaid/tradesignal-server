package com.tradesignal.service;

import com.tradesignal.model.AppState;
import com.tradesignal.model.Config;
import com.tradesignal.model.TradeLogEntry;
import com.tradesignal.store.DataStore;
import com.tradesignal.util.MarketHours;
import org.springframework.stereotype.Service;

import java.util.List;

/** Thin Facade the controller talks to \u2014 the actual work lives in
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

    public double availableCapital() {
        return execution.availableCapital(store.get());
    }

    public boolean isStorageDurable() {
        return store.isDurable();
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
        if (incoming.riskPerTradePct > 0 && incoming.riskPerTradePct <= 100) cfg.riskPerTradePct = incoming.riskPerTradePct;
        if (incoming.maxOrderPct > 0 && incoming.maxOrderPct <= 100) cfg.maxOrderPct = incoming.maxOrderPct;
        if ("percent".equals(incoming.exitMode) || "rupees".equals(incoming.exitMode)) cfg.exitMode = incoming.exitMode;
        if (incoming.targetRupees > 0) cfg.targetRupees = incoming.targetRupees;
        if (incoming.stopRupees > 0) cfg.stopRupees = incoming.stopRupees;
        // null or <=0 means "no cap \u2014 decide purely from market conditions", which is a valid, intentional choice here.
        cfg.maxOrdersPerDay = (incoming.maxOrdersPerDay != null && incoming.maxOrdersPerDay > 0) ? incoming.maxOrdersPerDay : null;
        if (incoming.maxLegsPerPosition > 0) cfg.maxLegsPerPosition = incoming.maxLegsPerPosition;
        store.save();
        scheduler.tick();
    }

    public String startTrade() {
        AppState state = store.get();
        if (state.position != null) return "A position is already open.";
        if (state.config.symbol == null || state.config.symbol.isBlank()) return "Set a symbol first.";
        if (state.lastSignal == null || state.lastSignal.error != null) return "No live price yet \u2014 try again in a few seconds.";
        String symbol = execution.fullSymbol(state.config.symbol, state.config.suffix);
        boolean ok = execution.openPosition(symbol, state.lastSignal.price, state.lastVolatilityPct);
        return ok ? null : "Investment amount is too small (or risk % too low) to buy even 1 share at this price.";
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
