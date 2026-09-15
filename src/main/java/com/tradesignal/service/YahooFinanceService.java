package com.tradesignal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Service
public class YahooFinanceService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public static class ChartData {
        public double regularMarketPrice;
        public List<Double> closes = new ArrayList<>();
        public List<Long> timestamps = new ArrayList<>();
    }

    public ChartData fetchChart(String symbol, String interval, String range) {
        String url = UriComponentsBuilder
                .fromHttpUrl("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol)
                .queryParam("interval", interval)
                .queryParam("range", range)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("HTTP " + response.getStatusCode());
        }

        JsonNode root;
        try {
            root = mapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Could not parse Yahoo response: " + e.getMessage());
        }
        JsonNode result = root.path("chart").path("result").get(0);
        if (result == null || result.isMissingNode()) {
            throw new RuntimeException("No data for symbol " + symbol);
        }

        ChartData data = new ChartData();
        JsonNode meta = result.path("meta");
        data.regularMarketPrice = meta.path("regularMarketPrice").asDouble();

        JsonNode timestampsNode = result.path("timestamp");
        for (JsonNode t : timestampsNode) {
            data.timestamps.add(t.asLong());
        }

        JsonNode closesNode = result.path("indicators").path("quote").get(0).path("close");
        for (JsonNode c : closesNode) {
            if (!c.isNull()) data.closes.add(c.asDouble());
        }
        if (data.closes.isEmpty() && data.regularMarketPrice == 0) {
            throw new RuntimeException("No price data returned for " + symbol);
        }
        if (data.regularMarketPrice == 0 && !data.closes.isEmpty()) {
            data.regularMarketPrice = data.closes.get(data.closes.size() - 1);
        }
        return data;
    }
}
