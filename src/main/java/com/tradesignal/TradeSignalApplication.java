package com.tradesignal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TradeSignalApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeSignalApplication.class, args);
    }
}
