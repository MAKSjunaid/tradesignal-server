package com.tradesignal.model;

import java.util.List;

public class SignalResult {
    public String action; // BUY | HOLD | SELL
    public List<String> reasons;
    public Double s20;
    public Double s50;
    public Double r14;
    public double price;
    public String symbol;
    public String at; // ISO-8601
    public String error; // set instead of the above when a fetch fails
}
//  GOOD
