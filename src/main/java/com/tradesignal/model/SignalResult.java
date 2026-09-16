package com.tradesignal.model;

import java.util.List;

public class SignalResult {
    public String action; // BUY | HOLD | SELL
    public List<String> reasons;
    public Double s20;
    public Double s50;
    public Double r14;
    public double price;
    /** Confluence score across the 5 factors (-5..+5) and the score needed to act. */
    public int score;
    public int threshold;
    public String symbol;
    public String at; // ISO-8601
    public String error; // set instead of the above when a fetch fails
}
