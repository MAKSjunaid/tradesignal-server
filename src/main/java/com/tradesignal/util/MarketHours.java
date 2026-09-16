package com.tradesignal.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Small shared helper for IST time and NSE/BSE market-hours checks. */
public final class MarketHours {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private MarketHours() {}

    public static ZonedDateTime istNow() {
        return ZonedDateTime.now(IST);
    }

    public static int minutesSinceMidnight(ZonedDateTime n) {
        return n.getHour() * 60 + n.getMinute();
    }

    /** NSE/BSE regular session: Mon-Fri, 9:15am - 3:30pm IST. */
    public static boolean marketOpenNow() {
        ZonedDateTime n = istNow();
        int mins = minutesSinceMidnight(n);
        int day = n.getDayOfWeek().getValue(); // 1=Mon .. 7=Sun
        return day >= 1 && day <= 5 && mins >= 555 && mins < 930;
    }

    public static String dayKeyIST(String isoInstant) {
        return ZonedDateTime.ofInstant(Instant.parse(isoInstant), IST).toLocalDate().toString();
    }
}

// GOOD
