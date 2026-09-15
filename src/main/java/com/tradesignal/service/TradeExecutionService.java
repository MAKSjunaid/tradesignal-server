package com.tradesignal.service;

import com.tradesignal.model.AppState;
import com.tradesignal.model.Config;
import com.tradesignal.model.Position;
import com.tradesignal.model.TradeLogEntry;
import com.tradesignal.store.DataStore;
import com.tradesignal.util.MarketHours;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Everything about opening, closing, and settling a paper-trade position. */
@Service
public class TradeExecutionService {

    private final DataStore store;

    public TradeExecutionService(DataStore store) {
        this.store = store;
    }

    public String fullSymbol(String sym, String suffix) {
        String s = sym == null ? "" : sym.trim().toUpperCase();
        if (s.endsWith(".NS") || s.endsWith(".BO")) return s;
        return s + suffix;
    }

    public boolean openPosition(String symbol, double price) {
        AppState state = store.get();
        Config cfg = state.config;
        int qty = (int) Math.floor(cfg.investment / price);
        if (qty < 1) return false;

        Position p = new Position();
        p.symbol = symbol;
        p.mode = cfg.mode;
        p.entryPrice = price;
        p.qty = qty;
        p.investedAmount = price * qty;
        p.entryTime = Instant.now().toString();
        p.target = price * (1 + cfg.targetPct / 100);
        p.stopLoss = price * (1 - cfg.stopPct / 100);

        state.position = p;
        store.save();
        return true;
    }

    public void closePosition(String reason, Double priceOverride) {
        AppState state = store.get();
        Position p = state.position;
        if (p == null) return;
        double exitPrice = priceOverride != null ? priceOverride : p.entryPrice;

        TradeLogEntry entry = new TradeLogEntry();
        entry.symbol = p.symbol;
        entry.mode = p.mode;
        entry.entryPrice = p.entryPrice;
        entry.qty = p.qty;
        entry.investedAmount = p.investedAmount;
        entry.entryTime = p.entryTime;
        entry.exitPrice = exitPrice;
        entry.exitTime = Instant.now().toString();
        entry.pnl = (exitPrice - p.entryPrice) * p.qty;
        entry.pnlPct = (exitPrice - p.entryPrice) / p.entryPrice * 100;
        entry.reason = reason;

        state.tradeLog.add(0, entry);
        state.position = null;
        store.save();
    }

    /** If the server was asleep/restarted right through an intraday square-off, settle it safely on next boot. */
    public void settleStaleIntraday() {
        AppState state = store.get();
        Position p = state.position;
        if (p == null) return;
        if (!"intraday".equals(p.mode)) return;
        if (MarketHours.dayKeyIST(p.entryTime).equals(MarketHours.dayKeyIST(Instant.now().toString()))) return;

        TradeLogEntry entry = new TradeLogEntry();
        entry.symbol = p.symbol;
        entry.mode = p.mode;
        entry.entryPrice = p.entryPrice;
        entry.qty = p.qty;
        entry.investedAmount = p.investedAmount;
        entry.entryTime = p.entryTime;
        entry.exitPrice = p.entryPrice;
        entry.exitTime = p.entryTime;
        entry.pnl = 0;
        entry.pnlPct = 0;
        entry.reason = "Auto-closed: intraday position left open from a previous session (server was likely asleep at close)";
        state.tradeLog.add(0, entry);
        state.position = null;
        store.save();
    }
}
