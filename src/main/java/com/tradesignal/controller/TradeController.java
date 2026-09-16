package com.tradesignal.controller;

import com.tradesignal.model.AppState;
import com.tradesignal.model.Config;
import com.tradesignal.service.TradingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TradeController {

    private final TradingService trading;

    public TradeController(TradingService trading) {
        this.trading = trading;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        AppState state = trading.getState();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", state.config);
        out.put("position", state.position);
        out.put("tradeLog", state.tradeLog.size() > 100 ? state.tradeLog.subList(0, 100) : state.tradeLog);
        out.put("lastSignal", state.lastSignal);
        out.put("lastChecked", state.lastChecked);
        out.put("lastVolatilityPct", state.lastVolatilityPct);
        out.put("nextMoveHint", state.nextMoveHint);
        out.put("availableCapital", trading.availableCapital());
        out.put("marketOpen", trading.marketOpenNow());
        out.put("istTime", Instant.now().toString());
        return out;
    }

    @PostMapping("/config")
    public Map<String, Object> config(@RequestBody Config incoming) {
        trading.updateConfig(incoming);
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("config", trading.getState().config);
        return out;
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        String error = trading.startTrade();
        Map<String, Object> out = new HashMap<>();
        if (error != null) {
            out.put("ok", false);
            out.put("error", error);
            return ResponseEntity.badRequest().body(out);
        }
        out.put("ok", true);
        out.put("position", trading.getState().position);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/exit")
    public ResponseEntity<Map<String, Object>> exit() {
        String error = trading.exitTrade();
        Map<String, Object> out = new HashMap<>();
        if (error != null) {
            out.put("ok", false);
            out.put("error", error);
            return ResponseEntity.badRequest().body(out);
        }
        out.put("ok", true);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        trading.reset();
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        return out;
    }

    @GetMapping("/log.json")
    public ResponseEntity<Object> logJson() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tradesignal-log.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(trading.getTradeLog());
    }

    @GetMapping("/ping")
    public String ping() {
        return "ok";
    }
}
