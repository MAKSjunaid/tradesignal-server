package com.tradesignal.service;

import com.tradesignal.model.AppState;
import com.tradesignal.model.Config;
import com.tradesignal.model.Position;
import com.tradesignal.model.TradeLogEntry;
import com.tradesignal.store.DataStore;
import com.tradesignal.util.MarketHours;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Everything About Opening, adding to, closing, and settling a paper-trade position. */
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

    /** How many trades were opened today (IST), for the maxOrdersPerDay cap. */
    public int ordersOpenedToday(AppState state) {
        String today = MarketHours.dayKeyIST(Instant.now().toString());
        int count = (int) state.tradeLog.stream()
                .filter(t -> t.entryTime != null && MarketHours.dayKeyIST(t.entryTime).equals(today))
                .count();
        if (state.position != null && MarketHours.dayKeyIST(state.position.entryTime).equals(today)) count++;
        return count;
    }

    /**
     * Risk-based, volatility-adjusted quantity for ONE buy tranche (leg).
     * 1. riskAmount = how much money you're willing to lose if the stop-loss hits.
     * 2. qtyByRisk  = riskAmount / (stop-loss distance in \u20b9 per share).
     * 3. Scaled by how today's ATR volatility compares to a normal baseline \u2014 choppier
     *    than usual shrinks the size, calmer than usual allows a bit more.
     * 4. Capped by maxOrderPct of capitalBase, AND by what's actually affordable right now,
     *    whichever is smaller.
     */
    private int computeLegQty(Config cfg, double price, Double volatilityPct, double capitalBase) {
        if (capitalBase <= 0) return 0;
        double riskAmount = capitalBase * (cfg.riskPerTradePct / 100.0);
        boolean rupeeMode = "rupees".equals(cfg.exitMode);
        double stopDistancePerShare = rupeeMode ? cfg.stopRupees : price * (cfg.stopPct / 100.0);
        if (stopDistancePerShare <= 0) return 0;
        int qtyByRisk = (int) Math.floor(riskAmount / stopDistancePerShare);

        double vol = (volatilityPct != null && volatilityPct > 0) ? volatilityPct : BASELINE_VOLATILITY_PCT;
        double volAdjust = clamp(BASELINE_VOLATILITY_PCT / vol, MIN_VOL_ADJUST, MAX_VOL_ADJUST);
        int qty = (int) Math.floor(qtyByRisk * volAdjust);

        int qtyCapByPct = (int) Math.floor((capitalBase * (cfg.maxOrderPct / 100.0)) / price);
        int qtyCapByCash = (int) Math.floor(capitalBase / price);
        qty = Math.min(qty, Math.min(qtyCapByPct, qtyCapByCash));
        return Math.max(qty, 0);
    }

    /** Opens a brand-new position (first leg). Only call when no position is currently open. */
    public boolean openPosition(String symbol, double price, Double volatilityPct) {
        AppState state = store.get();
        Config cfg = state.config;
        double capitalBase = availableCapital(state);
        int qty = computeLegQty(cfg, price, volatilityPct, capitalBase);
        if (qty < 1) return false;

        Position p = new Position();
        p.symbol = symbol;
        p.mode = cfg.mode;
        Position.PositionLeg leg = new Position.PositionLeg();
        leg.price = price;
        leg.qty = qty;
        leg.time = Instant.now().toString();
        p.legs.add(leg);
        p.entryTime = leg.time;

        state.position = p;
        recomputeAggregate(p, cfg);
        store.save();
        return true;
    }

    /**
     * Adds another buy tranche to an already-open position ("averaging in") \u2014 used when the
     * price has dropped since the last buy but the signal still says BUY, so the average entry
     * price is lowered and the (recalculated) target becomes easier to reach. Capped by
     * cfg.maxLegsPerPosition so this can't run away into an unbounded martingale.
     */
    public boolean addToPosition(double price, Double volatilityPct) {
        AppState state = store.get();
        Position p = state.position;
        Config cfg = state.config;
        if (p == null) return false;
        if (p.legs.size() >= cfg.maxLegsPerPosition) return false;

        double capitalBase = availableCapital(state); // already excludes what's locked in this position
        int qty = computeLegQty(cfg, price, volatilityPct, capitalBase);
        if (qty < 1) return false;

        Position.PositionLeg leg = new Position.PositionLeg();
        leg.price = price;
        leg.qty = qty;
        leg.time = Instant.now().toString();
        p.legs.add(leg);

        recomputeAggregate(p, cfg);
        store.save();
        return true;
    }

    /** Recomputes weighted-average entry, total qty/invested, and target/stop-loss from all legs. */
    private void recomputeAggregate(Position p, Config cfg) {
        int totalQty = 0;
        double totalCost = 0;
        for (Position.PositionLeg leg : p.legs) {
            totalQty += leg.qty;
            totalCost += leg.price * leg.qty;
        }
        p.qty = totalQty;
        p.entryPrice = totalCost / totalQty;
        p.investedAmount = totalCost;

        boolean rupeeMode = "rupees".equals(cfg.exitMode);
        p.target = rupeeMode ? p.entryPrice + cfg.targetRupees : p.entryPrice * (1 + cfg.targetPct / 100);
        p.stopLoss = rupeeMode ? p.entryPrice - cfg.stopRupees : p.entryPrice * (1 - cfg.stopPct / 100);

        String exitDesc = rupeeMode
                ? String.format("\u20b9%.2f target / \u20b9%.2f stop (absolute, from average entry)", cfg.targetRupees, cfg.stopRupees)
                : String.format("%.2f%% target / %.2f%% stop (from average entry)", cfg.targetPct, cfg.stopPct);
        p.sizingNote = String.format(
            "%d buy%s so far, averaged to \u20b9%.2f entry \u00d7 %d shares (\u20b9%.2f invested). %s.",
            p.legs.size(), p.legs.size() > 1 ? "s" : "", p.entryPrice, p.qty, p.investedAmount, exitDesc);
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
