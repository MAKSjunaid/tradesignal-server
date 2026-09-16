package com.tradesignal.service;

import com.tradesignal.model.AppState;
import com.tradesignal.model.Config;
import com.tradesignal.model.Position;
import com.tradesignal.model.TradeLogEntry;
import com.tradesignal.store.DataStore;
import com.tradesignal.util.MarketHours;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Everything About opening, closing, and settling a paper-trade position. */
@Service
public class TradeExecutionService {

    private final DataStore store;

    public TradeExecutionService(DataStore store) {
        this.store = store;
    }

    // "Normal" ATR for a liquid NSE/BSE stock, as a % of price \u2014 the reference point volatility
    // is compared against. Current volatility above this shrinks the position; below it, grows it
    // (within the MIN/MAX bounds below) so size responds to how choppy the stock actually is right now.
    private static final double BASELINE_VOLATILITY_PCT = 1.0;
    private static final double MIN_VOL_ADJUST = 0.25;
    private static final double MAX_VOL_ADJUST = 2.0;

    public String fullSymbol(String sym, String suffix) {
        String s = sym == null ? "" : sym.trim().toUpperCase();
        if (s.endsWith(".NS") || s.endsWith(".BO")) return s;
        return s + suffix;
    }

    /**
     * Current account equity: starting investment plus every closed trade's realised P&L,
     * minus whatever is currently locked in an open position. This is what sizing and the
     * "capital available" display use \u2014 so profits compound into bigger future orders and
     * losses shrink them, instead of every order always being sized off the original amount.
     */
    public double availableCapital(AppState state) {
        double realizedPnl = state.tradeLog.stream().mapToDouble(t -> t.pnl).sum();
        double equity = state.config.investment + realizedPnl;
        if (state.position != null) equity -= state.position.investedAmount;
        return equity;
    }

    /**
     * Risk-based, volatility-adjusted position sizing.
     * 1. riskAmount = how much money you're willing to lose if the stop-loss hits.
     * 2. qtyByRisk  = riskAmount / (stop-loss distance in \u20b9 per share).
     * 3. Scaled by how today's ATR volatility compares to a normal baseline \u2014 choppier
     *    than usual shrinks the size, calmer than usual allows a bit more.
     * 4. Capped by maxOrderPct regardless, as a hard safety ceiling.
     * All of this is based on current account equity (see availableCapital), not the
     * original investment amount, so sizing compounds with realised P&L over time.
     */
    public boolean openPosition(String symbol, double price, Double volatilityPct) {
        AppState state = store.get();
        Config cfg = state.config;
        double capitalBase = availableCapital(state);
        if (capitalBase <= 0) return false;

        double riskAmount = capitalBase * (cfg.riskPerTradePct / 100.0);
        double stopDistancePerShare = price * (cfg.stopPct / 100.0);
        if (stopDistancePerShare <= 0) return false;
        int qtyByRisk = (int) Math.floor(riskAmount / stopDistancePerShare);

        double vol = (volatilityPct != null && volatilityPct > 0) ? volatilityPct : BASELINE_VOLATILITY_PCT;
        double volAdjust = clamp(BASELINE_VOLATILITY_PCT / vol, MIN_VOL_ADJUST, MAX_VOL_ADJUST);
        int qty = (int) Math.floor(qtyByRisk * volAdjust);

        int qtyCap = (int) Math.floor((capitalBase * (cfg.maxOrderPct / 100.0)) / price);
        qty = Math.min(qty, qtyCap);
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
        p.sizingNote = String.format(
            "Risking %.1f%% of capital (\u20b9%.2f) over a %.1f%% stop \u2192 %d shares by risk, \u00d7%.2f for %.2f%% ATR volatility (baseline %.1f%%), capped at %.0f%% of capital.",
            cfg.riskPerTradePct, riskAmount, cfg.stopPct, qtyByRisk, volAdjust, vol, BASELINE_VOLATILITY_PCT, cfg.maxOrderPct);

        state.position = p;
        store.save();
        return true;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
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
        entry.sizingNote = p.sizingNote;

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
        entry.sizingNote = p.sizingNote;
        state.tradeLog.add(0, entry);
        state.position = null;
        store.save();
    }
}
