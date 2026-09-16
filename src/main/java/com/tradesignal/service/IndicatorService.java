package com.tradesignal.service;

import com.tradesignal.model.SignalResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IndicatorService {

    public Double sma(List<Double> arr, int period) {
        if (arr.size() < period) return null;
        List<Double> slice = arr.subList(arr.size() - period, arr.size());
        return slice.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public Double smaAt(List<Double> arr, int endIdxExclusive, int period) {
        if (endIdxExclusive < period) return null;
        List<Double> slice = arr.subList(endIdxExclusive - period, endIdxExclusive);
        return slice.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public Double rsi(List<Double> arr, int period) {
        if (arr.size() < period + 1) return null;
        double gains = 0, losses = 0;
        for (int i = arr.size() - period; i < arr.size(); i++) {
            double diff = arr.get(i) - arr.get(i - 1);
            if (diff >= 0) gains += diff; else losses -= diff;
        }
        double avgGain = gains / period, avgLoss = losses / period;
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /** EMA series aligned to the input list: result.get(0) corresponds to arr index (period-1). */
    private List<Double> emaSeries(List<Double> arr, int period) {
        List<Double> result = new ArrayList<>();
        if (arr.size() < period) return result;
        double multiplier = 2.0 / (period + 1);
        double emaPrev = arr.subList(0, period).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        result.add(emaPrev);
        for (int i = period; i < arr.size(); i++) {
            double emaCur = (arr.get(i) - emaPrev) * multiplier + emaPrev;
            result.add(emaCur);
            emaPrev = emaCur;
        }
        return result;
    }

    public static class MacdResult {
        public double macdLine;
        public double signalLine;
        public double histogram;
    }

    /** Standard MACD(12,26,9). Returns null if there isn't enough history yet. */
    public MacdResult macd(List<Double> closes) {
        int fastP = 12, slowP = 26, signalP = 9;
        if (closes.size() < slowP + signalP) return null;
        List<Double> fastSeries = emaSeries(closes, fastP);   // starts at index fastP-1
        List<Double> slowSeries = emaSeries(closes, slowP);   // starts at index slowP-1
        int offset = slowP - fastP;                           // align fastSeries to slowSeries' start
        if (offset >= fastSeries.size()) return null;
        List<Double> fastAligned = fastSeries.subList(offset, fastSeries.size());
        int n = Math.min(fastAligned.size(), slowSeries.size());
        List<Double> macdLineSeries = new ArrayList<>();
        for (int i = 0; i < n; i++) macdLineSeries.add(fastAligned.get(i) - slowSeries.get(i));
        List<Double> signalSeries = emaSeries(macdLineSeries, signalP);
        if (signalSeries.isEmpty()) return null;
        MacdResult r = new MacdResult();
        r.macdLine = macdLineSeries.get(macdLineSeries.size() - 1);
        r.signalLine = signalSeries.get(signalSeries.size() - 1);
        r.histogram = r.macdLine - r.signalLine;
        return r;
    }

    public static class BollingerResult {
        public double middle;
        public double upper;
        public double lower;
    }

    /** Standard 20-period, 2 standard-deviation Bollinger Bands. */
    public BollingerResult bollinger(List<Double> closes, int period, double stdDevMultiplier) {
        if (closes.size() < period) return null;
        List<Double> slice = closes.subList(closes.size() - period, closes.size());
        double mean = slice.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = slice.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        BollingerResult r = new BollingerResult();
        r.middle = mean;
        r.upper = mean + stdDevMultiplier * stdDev;
        r.lower = mean - stdDevMultiplier * stdDev;
        return r;
    }
    /**
     * Average True Range, expressed as a % of the latest close, over the given period.
     * This is a standard measure of how much a stock actually moves candle to candle \u2014
     * used here to size positions by real recent volatility instead of a flat percentage.
     */
    public Double atrPct(List<Double> highs, List<Double> lows, List<Double> closes, int period) {
        int n = closes.size();
        if (n < period + 1 || highs.size() != n || lows.size() != n) return null;
        List<Double> trueRanges = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            double highLow = highs.get(i) - lows.get(i);
            double highPrevClose = Math.abs(highs.get(i) - closes.get(i - 1));
            double lowPrevClose = Math.abs(lows.get(i) - closes.get(i - 1));
            trueRanges.add(Math.max(highLow, Math.max(highPrevClose, lowPrevClose)));
        }
        Double atr = sma(trueRanges, period);
        if (atr == null) return null;
        double lastClose = closes.get(n - 1);
        if (lastClose <= 0) return null;
        return (atr / lastClose) * 100;
    }

    public SignalResult decideSignal(List<Double> closes, double currentPrice, String mode) {
        boolean intraday = "intraday".equals(mode);
        // Intraday uses shorter, faster-reacting averages and a lower confidence bar so it
        // cycles through BUY/SELL/HOLD several times a day instead of waiting for one big trend.
        int shortPeriod = intraday ? 9 : 20;
        int longPeriod = intraday ? 21 : 50;
        int rsiPeriod = intraday ? 9 : 14;
        // 5 factors now contribute (SMA cross, RSI, price-vs-average, MACD, Bollinger), so the
        // threshold is out of a possible -5..+5, not -3..+3.
        int threshold = intraday ? 2 : 3;

        Double s20 = sma(closes, shortPeriod);
        Double s50 = sma(closes, longPeriod);
        Double r14 = rsi(closes, rsiPeriod);
        MacdResult macdResult = macd(closes);
        BollingerResult bb = bollinger(closes, 20, 2.0);
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (s20 != null && s50 != null) {
            if (s20 > s50) {
                score++;
                reasons.add(String.format("Short-term average (\u20b9%.2f) is above the long-term average (\u20b9%.2f) \u2014 uptrend", s20, s50));
            } else {
                score--;
                reasons.add("Short-term average is below the long-term average \u2014 downtrend");
            }
        } else {
            reasons.add(String.format("Not enough history yet for a %d-period average \u2014 signal is less reliable", longPeriod));
        }

        if (r14 != null) {
            if (r14 < 30) {
                score++;
                reasons.add(String.format("RSI is %.1f \u2014 oversold, possible bounce", r14));
            } else if (r14 > 70) {
                score--;
                reasons.add(String.format("RSI is %.1f \u2014 overbought, risk of pullback", r14));
            } else {
                reasons.add(String.format("RSI is %.1f \u2014 neutral", r14));
            }
        }

        if (s20 != null) {
            if (currentPrice > s20) {
                score++;
                reasons.add("Price is trading above its short-term average");
            } else {
                score--;
                reasons.add("Price is trading below its short-term average");
            }
        }

        if (macdResult != null) {
            if (macdResult.macdLine > macdResult.signalLine) {
                score++;
                reasons.add(String.format("MACD (%.3f) is above its signal line (%.3f) \u2014 bullish momentum", macdResult.macdLine, macdResult.signalLine));
            } else {
                score--;
                reasons.add(String.format("MACD (%.3f) is below its signal line (%.3f) \u2014 bearish momentum", macdResult.macdLine, macdResult.signalLine));
            }
        } else {
            reasons.add("Not enough history yet for MACD \u2014 momentum read skipped");
        }

        if (bb != null) {
            if (currentPrice <= bb.lower) {
                score++;
                reasons.add(String.format("Price (\u20b9%.2f) is at/below the lower Bollinger Band (\u20b9%.2f) \u2014 potential bounce", currentPrice, bb.lower));
            } else if (currentPrice >= bb.upper) {
                score--;
                reasons.add(String.format("Price (\u20b9%.2f) is at/above the upper Bollinger Band (\u20b9%.2f) \u2014 potential pullback", currentPrice, bb.upper));
            } else {
                reasons.add("Price is within its normal Bollinger Band range \u2014 no extreme");
            }
        } else {
            reasons.add("Not enough history yet for Bollinger Bands");
        }

        String action = score >= threshold ? "BUY" : score <= -threshold ? "SELL" : "HOLD";

        SignalResult result = new SignalResult();
        result.action = action;
        result.reasons = reasons;
        result.s20 = s20;
        result.s50 = s50;
        result.r14 = r14;
        result.price = currentPrice;
        result.score = score;
        result.threshold = threshold;
        return result;
    }
}
