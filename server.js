const express = require('express');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

const DATA_DIR = path.join(__dirname, 'data');
const STORE_PATH = path.join(DATA_DIR, 'store.json');
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });

const DEFAULT_STATE = {
  config: {
    symbol: '',
    suffix: '.NS',
    investment: 10000,
    mode: 'intraday',
    targetPct: 2,
    stopPct: 1,
    autoMode: false,
  },
  position: null,
  tradeLog: [],
  lastSignal: null,
  lastChecked: null,
};

function loadStore() {
  try {
    const raw = fs.readFileSync(STORE_PATH, 'utf8');
    return { ...DEFAULT_STATE, ...JSON.parse(raw) };
  } catch (e) {
    return JSON.parse(JSON.stringify(DEFAULT_STATE));
  }
}
function saveStore() {
  fs.writeFileSync(STORE_PATH, JSON.stringify(store, null, 2));
}

let store = loadStore();

// ---------- IST helpers ----------
function istNow() {
  return new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Kolkata' }));
}
function marketOpenNow() {
  const n = istNow();
  const mins = n.getHours() * 60 + n.getMinutes();
  const day = n.getDay();
  return day >= 1 && day <= 5 && mins >= 555 && mins < 930; // 9:15 - 15:30 IST
}
function dayKeyIST(iso) {
  return new Date(new Date(iso).toLocaleString('en-US', { timeZone: 'Asia/Kolkata' })).toDateString();
}

// ---------- Yahoo Finance ----------
async function fetchYahoo(symbol, interval, range) {
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?interval=${interval}&range=${range}`;
  const res = await fetch(url, { headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36' } });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const data = await res.json();
  const result = data && data.chart && data.chart.result && data.chart.result[0];
  if (!result) throw new Error('No data for symbol');
  return result;
}

// ---------- indicators ----------
function sma(arr, period) {
  if (arr.length < period) return null;
  const slice = arr.slice(-period);
  return slice.reduce((a, b) => a + b, 0) / period;
}
function smaAt(arr, endIdxExclusive, period) {
  if (endIdxExclusive < period) return null;
  const slice = arr.slice(endIdxExclusive - period, endIdxExclusive);
  return slice.reduce((a, b) => a + b, 0) / period;
}
function rsi(arr, period = 14) {
  if (arr.length < period + 1) return null;
  let gains = 0, losses = 0;
  for (let i = arr.length - period; i < arr.length; i++) {
    const diff = arr[i] - arr[i - 1];
    if (diff >= 0) gains += diff; else losses -= diff;
  }
  const avgGain = gains / period, avgLoss = losses / period;
  if (avgLoss === 0) return 100;
  const rs = avgGain / avgLoss;
  return 100 - 100 / (1 + rs);
}
function decideSignal(closes, currentPrice) {
  const clean = closes.filter((c) => typeof c === 'number');
  const s20 = sma(clean, 20), s50 = sma(clean, 50), r14 = rsi(clean, 14);
  let score = 0;
  const reasons = [];
  if (s20 != null && s50 != null) {
    if (s20 > s50) { score++; reasons.push(`Short-term average (₹${s20.toFixed(2)}) is above the long-term average (₹${s50.toFixed(2)}) — uptrend`); }
    else { score--; reasons.push('Short-term average is below the long-term average — downtrend'); }
  } else {
    reasons.push('Not enough history yet for a 50-period average — signal is less reliable');
  }
  if (r14 != null) {
    if (r14 < 30) { score++; reasons.push(`RSI is ${r14.toFixed(1)} — oversold, possible bounce`); }
    else if (r14 > 70) { score--; reasons.push(`RSI is ${r14.toFixed(1)} — overbought, risk of pullback`); }
    else reasons.push(`RSI is ${r14.toFixed(1)} — neutral`);
  }
  if (s20 != null) {
    if (currentPrice > s20) { score++; reasons.push('Price is trading above its short-term average'); }
    else { score--; reasons.push('Price is trading below its short-term average'); }
  }
  const action = score >= 2 ? 'BUY' : score <= -2 ? 'SELL' : 'HOLD';
  return { action, reasons, s20, s50, r14 };
}

// ---------- trading logic ----------
function fullSymbol(sym, suffix) {
  const s = (sym || '').trim().toUpperCase();
  if (s.endsWith('.NS') || s.endsWith('.BO')) return s;
  return s + suffix;
}

function openPosition(symbol, price) {
  const cfg = store.config;
  const qty = Math.floor(cfg.investment / price);
  if (qty < 1) return false;
  store.position = {
    symbol,
    mode: cfg.mode,
    entryPrice: price,
    qty,
    entryTime: new Date().toISOString(),
    target: price * (1 + cfg.targetPct / 100),
    stopLoss: price * (1 - cfg.stopPct / 100),
  };
  saveStore();
  return true;
}
function closePosition(reason, price) {
  const p = store.position;
  if (!p) return;
  const exitPrice = price ?? p.entryPrice;
  const pnl = (exitPrice - p.entryPrice) * p.qty;
  const pnlPct = ((exitPrice - p.entryPrice) / p.entryPrice) * 100;
  store.tradeLog.unshift({
    symbol: p.symbol, mode: p.mode, entryPrice: p.entryPrice, qty: p.qty,
    entryTime: p.entryTime, exitPrice, exitTime: new Date().toISOString(),
    pnl, pnlPct, reason,
  });
  store.position = null;
  saveStore();
}

// Settle a stale intraday position left open from a previous calendar day
// (can happen if the server was asleep/restarted right through the 3:20pm cutoff).
function settleStaleIntraday() {
  const p = store.position;
  if (!p) return;
  if (p.mode === 'intraday' && dayKeyIST(p.entryTime) !== dayKeyIST(new Date().toISOString())) {
    store.tradeLog.unshift({
      symbol: p.symbol, mode: p.mode, entryPrice: p.entryPrice, qty: p.qty,
      entryTime: p.entryTime, exitPrice: p.entryPrice, exitTime: p.entryTime,
      pnl: 0, pnlPct: 0, reason: 'Auto-closed: intraday position left open from a previous session (server was likely asleep at close)',
    });
    store.position = null;
    saveStore();
  }
}
settleStaleIntraday();

async function tick() {
  const cfg = store.config;
  if (!cfg.symbol) return;
  const symbol = fullSymbol(cfg.symbol, cfg.suffix);
  let result;
  try {
    const interval = cfg.mode === 'intraday' ? '5m' : '1d';
    const range = cfg.mode === 'intraday' ? '5d' : '1y';
    result = await fetchYahoo(symbol, interval, range);
  } catch (e) {
    store.lastSignal = { error: e.message, at: new Date().toISOString() };
    return;
  }
  const closes = (result.indicators.quote[0].close || []).filter((c) => c != null);
  const price = result.meta.regularMarketPrice ?? closes[closes.length - 1];
  const sig = decideSignal(closes, price);
  store.lastSignal = { ...sig, price, symbol, at: new Date().toISOString() };
  store.lastChecked = new Date().toISOString();

  const n = istNow();
  const mins = n.getHours() * 60 + n.getMinutes();
  const open = marketOpenNow();

  if (store.position) {
    // Exit conditions always apply, regardless of auto mode, once a trade is open.
    if (price >= store.position.target) { closePosition('Target hit', price); }
    else if (price <= store.position.stopLoss) { closePosition('Stop-loss hit', price); }
    else if (store.position.mode === 'intraday' && mins >= 920) { closePosition('Auto square-off before market close', price); }
    else if (cfg.autoMode && sig.action === 'SELL') { closePosition('Signal turned SELL', price); }
  } else if (cfg.autoMode && open) {
    if (!(cfg.mode === 'intraday' && mins >= 900) && sig.action === 'BUY') {
      openPosition(symbol, price);
    }
  }
  saveStore();
}

setInterval(() => { tick().catch(() => {}); }, 30000);
tick().catch(() => {});

// ---------- API ----------
app.get('/api/state', (req, res) => {
  res.json({
    config: store.config,
    position: store.position,
    tradeLog: store.tradeLog.slice(0, 100),
    lastSignal: store.lastSignal,
    lastChecked: store.lastChecked,
    marketOpen: marketOpenNow(),
    istTime: istNow().toISOString(),
  });
});

app.post('/api/config', (req, res) => {
  const b = req.body || {};
  const cfg = store.config;
  if (typeof b.symbol === 'string') cfg.symbol = b.symbol.trim();
  if (b.suffix === '.NS' || b.suffix === '.BO') cfg.suffix = b.suffix;
  if (typeof b.investment === 'number' && b.investment >= 0) cfg.investment = b.investment;
  if (b.mode === 'intraday' || b.mode === 'longterm') cfg.mode = b.mode;
  if (typeof b.targetPct === 'number') cfg.targetPct = b.targetPct;
  if (typeof b.stopPct === 'number') cfg.stopPct = b.stopPct;
  if (typeof b.autoMode === 'boolean') cfg.autoMode = b.autoMode;
  saveStore();
  tick().catch(() => {});
  res.json({ ok: true, config: cfg });
});

app.post('/api/start', async (req, res) => {
  if (store.position) return res.status(400).json({ ok: false, error: 'A position is already open.' });
  if (!store.config.symbol) return res.status(400).json({ ok: false, error: 'Set a symbol first.' });
  const symbol = fullSymbol(store.config.symbol, store.config.suffix);
  const price = store.lastSignal && store.lastSignal.price;
  if (!price) return res.status(400).json({ ok: false, error: 'No live price yet — try again in a few seconds.' });
  const ok = openPosition(symbol, price);
  if (!ok) return res.status(400).json({ ok: false, error: 'Investment amount is too small to buy 1 share at this price.' });
  res.json({ ok: true, position: store.position });
});

app.post('/api/exit', (req, res) => {
  if (!store.position) return res.status(400).json({ ok: false, error: 'No open position.' });
  const price = (store.lastSignal && store.lastSignal.price) || store.position.entryPrice;
  closePosition('Manual exit', price);
  res.json({ ok: true });
});

app.get('/api/log.json', (req, res) => {
  res.setHeader('Content-Disposition', 'attachment; filename="tradesignal-log.json"');
  res.json(store.tradeLog);
});

app.post('/api/reset', (req, res) => {
  store = JSON.parse(JSON.stringify(DEFAULT_STATE));
  saveStore();
  res.json({ ok: true });
});

app.get('/api/ping', (req, res) => res.send('ok'));

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log('TradeSignal server running on port ' + PORT));
