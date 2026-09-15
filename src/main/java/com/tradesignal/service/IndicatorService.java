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

    public SignalResult decideSignal(List<Double> closes, double currentPrice) {
        Double s20 = sma(closes, 20);
        Double s50 = sma(closes, 50);
        Double r14 = rsi(closes, 14);
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
            reasons.add("Not enough history yet for a 50-period average \u2014 signal is less reliable");
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

        String action = score >= 2 ? "BUY" : score <= -2 ? "SELL" : "HOLD";

        SignalResult result = new SignalResult();
        result.action = action;
        result.reasons = reasons;
        result.s20 = s20;
        result.s50 = s50;
        result.r14 = r14;
        result.price = currentPrice;
        return result;
    }
}
