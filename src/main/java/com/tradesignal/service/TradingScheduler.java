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

        SignalResult sig = indicators.decideSignal(data.closes, data.regularMarketPrice);
        sig.symbol = symbol;
        sig.at = Instant.now().toString();
        state.lastSignal = sig;
        state.lastChecked = Instant.now().toString();

        ZonedDateTime n = MarketHours.istNow();
        int mins = MarketHours.minutesSinceMidnight(n);
        boolean open = MarketHours.marketOpenNow();
        double price = data.regularMarketPrice;

        if (state.position != null) {
            actOnOpenPosition(state, cfg, sig, price, mins);
        } else if (cfg.autoMode && open) {
            maybeEnter(cfg, sig, symbol, price, mins);
        }
        store.save();
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

    private void maybeEnter(Config cfg, SignalResult sig, String symbol, double price, int mins) {
        boolean tooLateForIntraday = "intraday".equals(cfg.mode) && mins >= 900;
        if (!tooLateForIntraday && "BUY".equals(sig.action)) {
            execution.openPosition(symbol, price);
        }
    }
}
