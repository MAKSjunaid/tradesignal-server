# TradeSignal — always-on paper trading engine

A small Node/Express app that watches one NSE/BSE stock at a time, decides
BUY / HOLD / SELL from a simple SMA + RSI heuristic, and (optionally) runs
paper trades automatically — including exiting before the 3:30pm close.
Because it runs on a server instead of your browser, it keeps working after
you close the tab, and you can check it from your phone or any other device.

**This never places real orders.** It's a paper-trading / learning tool.

## Run it locally first (optional)

```bash
npm install
npm start
```

Open http://localhost:3000, set a symbol, investment amount, target/stop %,
and flip "Auto BUY/SELL/HOLD" on if you want it to enter trades by itself.

## 1. Push to GitHub

```bash
cd tradesignal-server
git init
git add .
git commit -m "TradeSignal paper trading engine"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

## 2. Deploy on Render

1. In the Render dashboard: **New → Web Service**.
2. Connect the GitHub repo you just pushed.
3. Settings:
   - **Build command:** `npm install`
   - **Start command:** `npm start`
   - **Instance type:** Free works to try it out (see caveat below); pick
     **Starter** ($7/mo) if you want it genuinely always-on during market hours.
4. Deploy. Render gives you a URL like `https://tradesignal.onrender.com` —
   open that from any device to see the same dashboard and trade log.

## Important limitations to know before you rely on this

- **Render's free plan sleeps.** A free web service spins down after ~15
  minutes with no incoming HTTP requests, which pauses the background price
  check (and therefore auto-trading) until something wakes it back up. Two
  ways around this:
  - Use a free uptime pinger (e.g. [UptimeRobot](https://uptimerobot.com) or
    [cron-job.org](https://cron-job.org)) to hit `https://<your-app>.onrender.com/api/ping`
    every 5–10 minutes during market hours (9:15am–3:30pm IST). This keeps
    the free instance awake — good enough for most personal use.
  - Or move to Render's paid **Starter** plan, which doesn't sleep at all.
- **The free plan's disk isn't guaranteed to survive a redeploy.** The trade
  log (`data/store.json`) persists fine while the service is just sleeping
  and waking up, but a fresh deploy (e.g. pushing new code) can reset it. If
  you want trade history to survive redeploys permanently, attach a
  [Render Disk](https://render.com/docs/disks) (needs a paid instance type)
  or point the app at a small external database instead of the local file.
  For now, use "Download JSON" on the dashboard to back up your history
  whenever you like.
- The BUY/SELL logic is a simple, transparent SMA(20/50) + RSI(14) heuristic
  — a starting point to watch and refine, not a validated strategy or advice.
- If Yahoo Finance blocks or rate-limits a request, that check is skipped
  and retried automatically 30 seconds later — you'll see the error under
  the signal card if it keeps failing.

## What auto mode actually does

Every 30 seconds, whether or not anyone has the dashboard open, the server:
1. Fetches the latest price and recent history for your configured symbol.
2. Computes the BUY / HOLD / SELL signal.
3. If a position is open: closes it on target hit, stop-loss hit, a SELL
   signal (only when auto mode is on), or — for intraday — automatically at
   3:20pm IST, ten minutes ahead of the market close.
4. If no position is open and auto mode is on: opens one when the signal is
   BUY (and, for intraday, not after 3:00pm, so there's time to manage it
   before the close).

You can also skip auto mode and just use "Start paper trade now" /
"Exit trade now" manually — the target/stop/square-off exits still apply
either way once a trade is open.
