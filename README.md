# TradeSignal (Spring Boot)

Same always-on NSE/BSE paper-trading engine as before, rebuilt as a Java /
Spring Boot backend with a plain HTML + CSS + JS frontend (no framework,
no build step for the frontend — Spring just serves the static files).

Every order now tracks **how much money it actually used** —
`investedAmount = entryPrice × qty` — shown on the open position card, in
every row of the trade log, and totalled in "Capital deployed today".

**This never places real orders.** It's a paper-trading / learning tool.

## Project layout

```
src/main/java/com/tradesignal/
  TradeSignalApplication.java      Spring Boot entry point (+ @EnableScheduling)
  model/                           Config, Position, TradeLogEntry, SignalResult, AppState
  util/MarketHours.java            IST time + market-open helpers, shared across services
  store/DataStore.java             loads/saves data/store.json
  service/YahooFinanceService.java Yahoo chart API client
  service/IndicatorService.java    SMA / RSI / BUY-HOLD-SELL rule
  service/TradeExecutionService.java  open / close / settle a paper-trade position
  service/TradingScheduler.java    the 30s @Scheduled loop \u2014 fetches, decides, acts
  service/TradingService.java      thin facade the controller talks to
  controller/TradeController.java  REST API (/api/...)
src/main/resources/
  application.properties
  static/index.html, static/css/style.css, static/js/app.js   the dashboard
```

## Run it in IntelliJ

1. **File → Open** and pick this folder. IntelliJ detects the `pom.xml`
   and imports it as a Maven project (Community edition is fine — no
   Ultimate/Node plugin needed, this is now plain Java).
2. Let Maven download dependencies (bottom-right progress bar).
3. Open `TradeSignalApplication.java` and click the green ▶ run icon next
   to `public static void main`, or right-click it → Run.
4. Once you see `Started TradeSignalApplication`, open
   http://localhost:8080 in your browser.

No `npm install` step this time — Maven handles dependencies, and Spring
Boot serves the frontend files directly from `static/`.

## Deploy on Render

1. Push this folder to a new GitHub repo (same as before — see the earlier
   instructions if you need the exact git commands).
2. Render → **New → Web Service** → connect that repo.
3. Settings:
   - **Runtime:** Docker is not needed — pick **Java** if offered, or use:
     - **Build command:** `./mvnw clean package -DskipTests` (or `mvn clean package -DskipTests` if you don't have the wrapper)
     - **Start command:** `java -jar target/tradesignal-server-1.0.0.jar`
   - **Instance type:** Free to try it out; Starter ($7/mo) if you want it to never sleep.
4. Deploy. Same free-tier caveats as before apply (sleep after ~15 min
   idle, disk not guaranteed to survive a redeploy) — see the note below.

> If Render doesn't offer a Maven wrapper, generate one locally first with
> `mvn -N io.takari:maven:wrapper` and commit the resulting `mvnw` /
> `mvnw.cmd` / `.mvn/` files, or just set the build command to plain `mvn
> clean package -DskipTests` if Render's Java image already has Maven.

## Same limitations as the Node version

- Free Render web services sleep after ~15 minutes idle — use a free
  uptime pinger (UptimeRobot / cron-job.org) hitting `/api/ping` every
  5–10 minutes during market hours, or upgrade to Starter for always-on.
- The free plan's disk isn't guaranteed to survive a redeploy — use
  "Download JSON" on the dashboard to back up your trade history.
- The BUY/SELL logic is a simple SMA(20/50) + RSI(14) heuristic — a
  starting point to watch and refine, not investment advice.

## API summary

| Method | Path            | What it does |
|--------|-----------------|--------------|
| GET    | `/api/state`    | current config, position, trade log, last signal |
| POST   | `/api/config`   | save symbol/investment/mode/target/stop/auto-mode |
| POST   | `/api/start`    | manually open a paper trade at the last fetched price |
| POST   | `/api/exit`     | manually close the open trade |
| POST   | `/api/reset`    | wipe position + trade log + config back to defaults |
| GET    | `/api/log.json` | download the trade log as JSON |
| GET    | `/api/ping`     | health check / keep-alive target |
