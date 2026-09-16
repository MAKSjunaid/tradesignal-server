package com.tradesignal.service;

import com.tradesignal.model.AppState;
import com.tradesignal.model.Config;
import com.tradesignal.model.SignalResult;
import com.tradesignal.store.DataStore;
import com.tradesignal.util.MarketHours;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZonedDateTime;

/** Runs every 30s: fetches the price, computes the signal, and acts on it. */
@Service
public class TradingScheduler {

    private final DataStore store;
    private final YahooFinanceService yahoo;
    private final IndicatorService indicators;
    private final TradeExecutionService execution;

    public TradingScheduler(DataStore store, YahooFinanceService yahoo,
                             IndicatorService indicators, TradeExecutionService execution) {
        this.store = store;
        this.yahoo = yahoo;
        this.indicators = indicators;
        this.execution = execution;
    }

    @Scheduled(fixedRate = 30000, initialDelay = 2000)
    public void tick() {
        execution.settleStaleIntraday();
        AppState state = store.get();
        Config cfg = state.config;
        if (cfg.symbol == null || cfg.symbol.isBlank()) return;

        String symbol = execution.fullSymbol(cfg.symbol, cfg.suffix);
        YahooFinanceService.ChartData data;
        try {
            String interval = "intraday".equals(cfg.mode) ? "5m" : "1d";
            String range = "intraday".equals(cfg.mode) ? "5d" : "1y";
            data = yahoo.fetchChart(symbol, interval, range);
        } catch (Exception e) {
            SignalResult err = new SignalResult();
            err.error = e.getMessage();
            err.at = Instant.now().toString();
            state.lastSignal = err;
            store.save();
            return;
        }

        SignalResult sig = indicators.decideSignal(data.closes, data.regularMarketPrice, cfg.mode);
        sig.symbol = symbol;
        sig.at = Instant.now().toString();
        state.lastSignal = sig;
        state.lastChecked = Instant.now().toString();
        state.lastVolatilityPct = indicators.atrPct(data.highs, data.lows, data.closes, 14);

        ZonedDateTime n = MarketHours.istNow();
        int mins = MarketHours.minutesSinceMidnight(n);
        boolean open = MarketHours.marketOpenNow();
        double price = data.regularMarketPrice;

        if (state.position != null) {
            actOnOpenPosition(state, cfg, sig, price, mins);
        } else if (cfg.autoMode && open) {
            maybeEnter(cfg, sig, symbol, price, mins, state.lastVolatilityPct);
        }
        state.nextMoveHint = buildNextMoveHint(state, cfg, sig, price, mins);
        store.save();
    }

    private String buildNextMoveHint(AppState state, Config cfg, SignalResult sig, double price, int mins) {
        if (state.position != null) {
            double toTargetPct = Math.abs((state.position.target - price) / price) * 100;
            double toStopPct = Math.abs((price - state.position.stopLoss) / price) * 100;
            if (toStopPct <= toTargetPct) {
                return String.format("%.2f%% (\u20b9%.2f) away from the stop-loss at \u20b9%.2f \u2014 may SELL to limit loss if it drops further.",
                        toStopPct, Math.abs(price - state.position.stopLoss), state.position.stopLoss);
            }
            return String.format("%.2f%% (\u20b9%.2f) away from the target at \u20b9%.2f \u2014 may SELL to book profit if it rises further.",
                    toTargetPct, Math.abs(state.position.target - price), state.position.target);
        }
        if (!cfg.autoMode) return "Auto mode is OFF \u2014 no automatic entry will happen. Switch it on and press Save setup.";
        if (!MarketHours.marketOpenNow()) return "Market is closed \u2014 auto trading resumes at 9:15am IST on the next trading day.";

        int ordersToday = execution.ordersOpenedToday(state);
        if (ordersToday >= cfg.maxOrdersPerDay) {
            return String.format("Daily order cap reached (%d of %d) \u2014 no more entries today.", ordersToday, cfg.maxOrdersPerDay);
        }
        if ("intraday".equals(cfg.mode) && mins >= 900) {
            return "Too close to market close to open a new intraday trade today \u2014 waiting for the next session.";
        }
        if ("BUY".equals(sig.action)) return "Signal is BUY \u2014 entering on the next check (within 30s).";
        return String.format("Signal is %s (confidence %+d, needs %+d to buy) \u2014 waiting for a BUY. %d/%d orders used today.",
                sig.action, sig.score, sig.threshold, ordersToday, cfg.maxOrdersPerDay);
    }

    private void actOnOpenPosition(AppState state, Config cfg, SignalResult sig, double price, int mins) {
        if (price >= state.position.target) {
            execution.closePosition("Target hit", price);
        } else if (price <= state.position.stopLoss) {
            execution.closePosition("Stop-loss hit", price);
        } else if ("intraday".equals(state.position.mode) && mins >= 920) {
            execution.closePosition("Auto square-off before market close", price);
        } else if (cfg.autoMode && "SELL".equals(sig.action)) {
            execution.closePosition("Signal turned SELL", price);
        }
    }

    private void maybeEnter(Config cfg, SignalResult sig, String symbol, double price, int mins, Double volatilityPct) {
        boolean tooLateForIntraday = "intraday".equals(cfg.mode) && mins >= 900;
        if (tooLateForIntraday) return;
        if (execution.ordersOpenedToday(store.get()) >= cfg.maxOrdersPerDay) return;
        if ("BUY".equals(sig.action)) {
            execution.openPosition(symbol, price, volatilityPct);
        }
    }
}
