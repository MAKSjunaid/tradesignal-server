let mode = 'intraday', autoMode = false, exitMode = 'rupees';

// ---------- Indian-style currency formatting (lakh/crore comma grouping) ----------
function formatINR(n){
  n = n||0;
  return '₹' + n.toLocaleString('en-IN', {minimumFractionDigits:2, maximumFractionDigits:2});
}
function inrWordsLabel(n){
  const abs = Math.abs(n||0);
  const trim = (v)=> v.toFixed(2).replace(/\.00$/,'').replace(/0$/,'').replace(/\.$/,'');
  if (abs >= 1e7) return trim(n/1e7) + ' Crore';
  if (abs >= 1e5) return trim(n/1e5) + ' Lakh';
  if (abs >= 1e3) return trim(n/1e3) + ' Thousand';
  return null;
}
function updateInvestmentWords(){
  const inv = parseFloat(document.getElementById('investment').value)||0;
  const words = inrWordsLabel(inv);
  document.getElementById('investmentWords').textContent = words ? `= ${formatINR(inv)} (${words})` : '';
}

// ---------- button click feedback ----------
function flashButton(btn, busyText, doneText, promise){
  const original = btn.textContent;
  btn.disabled = true;
  btn.textContent = busyText;
  return promise.finally(()=>{
    btn.textContent = doneText;
    btn.classList.add('btn-flash');
    setTimeout(()=>{
      btn.textContent = original;
      btn.disabled = false;
      btn.classList.remove('btn-flash');
    }, 900);
  });
}

function applyExitMode(){
  document.querySelectorAll('#exitModeSeg button').forEach(b=>b.classList.toggle('active', b.dataset.exit===exitMode));
  document.getElementById('percentRow').style.display = exitMode==='percent' ? 'grid' : 'none';
  document.getElementById('rupeeRow').style.display = exitMode==='rupees' ? 'grid' : 'none';
}
document.getElementById('exitModeSeg').addEventListener('click', e=>{
  const b=e.target.closest('button'); if(!b) return;
  exitMode = b.dataset.exit;
  formTouched = true;
  applyExitMode();
});

function istNow(){ return new Date(new Date().toLocaleString('en-US',{timeZone:'Asia/Kolkata'})); }
function tickClock(){
  const n = istNow();
  document.getElementById('istClock').textContent = String(n.getHours()).padStart(2,'0')+':'+String(n.getMinutes()).padStart(2,'0')+':'+String(n.getSeconds()).padStart(2,'0')+' IST';
}
setInterval(tickClock,1000); tickClock();

document.getElementById('modeSeg').addEventListener('click', e=>{
  const b=e.target.closest('button'); if(!b) return;
  mode=b.dataset.mode;
  formTouched = true;
  document.querySelectorAll('#modeSeg button').forEach(x=>x.classList.toggle('active',x===b));
});
document.getElementById('autoSeg').addEventListener('click', e=>{
  const b=e.target.closest('button'); if(!b) return;
  autoMode = b.dataset.auto==='on';
  formTouched = true;
  document.querySelectorAll('#autoSeg button').forEach(x=>x.classList.toggle('active',x===b));
  updateSaveButtonLabel();
});
function updateSaveButtonLabel(){
  document.getElementById('saveBtn').textContent = autoMode ? 'Auto Run' : 'Save setup';
}

document.getElementById('saveBtn').addEventListener('click', ()=>{
  const btn = document.getElementById('saveBtn');
  const errBox=document.getElementById('errBox'); errBox.style.display='none';
  const maxOrdersRaw = document.getElementById('maxOrdersPerDay').value;
  const body = {
    symbol: document.getElementById('symbol').value,
    suffix: document.getElementById('exchange').value,
    investment: parseFloat(document.getElementById('investment').value)||0,
    riskPerTradePct: parseFloat(document.getElementById('riskPerTradePct').value)||1,
    maxOrderPct: parseFloat(document.getElementById('maxOrderPct').value)||50,
    mode, autoMode, exitMode,
    targetPct: parseFloat(document.getElementById('targetPct').value)||0,
    stopPct: parseFloat(document.getElementById('stopPct').value)||0,
    targetRupees: parseFloat(document.getElementById('targetRupees').value)||0,
    stopRupees: parseFloat(document.getElementById('stopRupees').value)||0,
    maxOrdersPerDay: maxOrdersRaw==='' ? null : parseInt(maxOrdersRaw),
    maxLegsPerPosition: parseInt(document.getElementById('maxLegsPerPosition').value)||3,
  };
  const busyLabel = autoMode ? 'Starting…' : 'Saving…';
  const doneLabel = autoMode ? 'Auto Run ✓' : 'Saved ✓';
  const req = fetch('/api/config', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)})
    .then(r=>{ if(!r.ok) throw new Error('save failed'); return refresh(); })
    .catch(()=>{ errBox.textContent='Could not save setup.'; errBox.style.display='block'; });
  flashButton(btn, busyLabel, doneLabel, req);
});

document.getElementById('manualStartBtn').addEventListener('click', ()=>{
  const btn = document.getElementById('manualStartBtn');
  const errBox=document.getElementById('errBox'); errBox.style.display='none';
  const req = fetch('/api/start', {method:'POST'})
    .then(r=>r.json())
    .then(data=>{
      if(!data.ok){ errBox.textContent = data.error; errBox.style.display='block'; return; }
      return refresh();
    })
    .catch(()=>{ errBox.textContent='Could not start trade.'; errBox.style.display='block'; });
  flashButton(btn, 'Starting…', 'Started ✓', req);
});
document.getElementById('exitBtn').addEventListener('click', ()=>{
  const btn = document.getElementById('exitBtn');
  const req = fetch('/api/exit', {method:'POST'}).then(()=>refresh());
  flashButton(btn, 'Exiting…', 'Exited ✓', req);
});
document.getElementById('resetBtn').addEventListener('click', ()=>{
  if(!confirm('This closes any open trade, clears the entire trade log, and resets settings back to defaults. This cannot be undone. Continue?')) return;
  const btn = document.getElementById('resetBtn');
  const req = fetch('/api/reset', {method:'POST'}).then(()=>{ formTouched = false; return refresh(); });
  flashButton(btn, 'Resetting…', 'Reset ✓', req);
});

function dateLabel(iso){
  const d = new Date(new Date(iso).toLocaleString('en-US',{timeZone:'Asia/Kolkata'}));
  const today = istNow().toDateString();
  const yest = new Date(istNow().getTime()-86400000).toDateString();
  if(d.toDateString()===today) return 'Today';
  if(d.toDateString()===yest) return 'Yesterday';
  return d.toLocaleDateString('en-IN',{day:'numeric',month:'short',year:'numeric'});
}

function renderLog(log){
  const wrap = document.getElementById('logWrap');
  if(!log.length){ wrap.innerHTML='<div class="empty">No trades yet.</div>'; return; }
  let html='', lastLabel=null;
  for(const t of log){
    const label = dateLabel(t.exitTime);
    if(label!==lastLabel){
      if(lastLabel!==null) html+='</tbody></table>';
      html += `<div class="hint" style="margin-top:14px;font-weight:600;color:var(--text);">${label}</div>`;
      html += '<table><thead><tr><th>Symbol</th><th>Mode</th><th>Invested</th><th>Buy time / price</th><th>Sell time / price</th><th>Qty</th><th>P&amp;L</th><th>Reason</th></tr></thead><tbody>';
      lastLabel=label;
    }
    const cls = t.pnl>=0?'pnl-pos':'pnl-neg';
    const buyTime = new Date(t.entryTime).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit'});
    const sellTime = new Date(t.exitTime).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit'});
    html += `<tr>
      <td data-label="Symbol">${t.symbol}</td>
      <td data-label="Mode">${t.mode}</td>
      <td data-label="Invested">${formatINR(t.investedAmount)}</td>
      <td data-label="Buy">${buyTime} · ${formatINR(t.entryPrice)}</td>
      <td data-label="Sell">${sellTime} · ${formatINR(t.exitPrice)}</td>
      <td data-label="Qty">${t.qty}</td>
      <td data-label="P&amp;L" class="${cls}">${t.pnl>=0?'+':''}${formatINR(t.pnl)} (${t.pnlPct.toFixed(2)}%)</td>
      <td data-label="Reason">${t.reason}</td>
    </tr>`;
  }
  html+='</tbody></table>';
  wrap.innerHTML = html;
}

function renderDaySummary(log, position){
  const todayStr = istNow().toDateString();
  const todays = log.filter(t => new Date(new Date(t.exitTime).toLocaleString('en-US',{timeZone:'Asia/Kolkata'})).toDateString()===todayStr);
  const total = todays.reduce((a,t)=>a+t.pnl,0);
  const el = document.getElementById('dayPnl');
  el.textContent = (total>=0?'+':'')+formatINR(total);
  el.className = 'v ' + (total>=0?'pnl-pos':'pnl-neg');
  document.getElementById('dayTrades').textContent = todays.length;
  const wins = todays.filter(t=>t.pnl>0).length;
  document.getElementById('dayWinRate').textContent = todays.length ? Math.round(wins/todays.length*100)+'%' : '—';

  // Capital deployed today = money used by today's closed trades, plus any position still open from today.
  let invested = todays.reduce((a,t)=>a+t.investedAmount,0);
  if(position && dateLabel(position.entryTime)==='Today') invested += position.investedAmount;
  document.getElementById('dayInvested').textContent = formatINR(invested);
}

let formTouched = false;
['symbol','investment','riskPerTradePct','maxOrderPct','targetPct','stopPct','targetRupees','stopRupees','maxOrdersPerDay','maxLegsPerPosition'].forEach(id=>{
  document.getElementById(id).addEventListener('input', ()=>formTouched=true);
});
document.getElementById('investment').addEventListener('input', updateInvestmentWords);

async function refresh(){
  let data;
  try{
    const r = await fetch('/api/state');
    data = await r.json();
  }catch(e){ return; }

  const dot = document.getElementById('marketDot'), txt = document.getElementById('marketText');
  dot.className = 'dot ' + (data.marketOpen?'open':'closed');
  txt.textContent = data.marketOpen ? 'Market open' : 'Market closed';

  if(!formTouched){
    document.getElementById('symbol').value = data.config.symbol || '';
    document.getElementById('exchange').value = data.config.suffix;
    document.getElementById('investment').value = data.config.investment;
    document.getElementById('riskPerTradePct').value = data.config.riskPerTradePct;
    document.getElementById('maxOrderPct').value = data.config.maxOrderPct;
    document.getElementById('targetPct').value = data.config.targetPct;
    document.getElementById('stopPct').value = data.config.stopPct;
    document.getElementById('targetRupees').value = data.config.targetRupees;
    document.getElementById('stopRupees').value = data.config.stopRupees;
    document.getElementById('maxOrdersPerDay').value = (data.config.maxOrdersPerDay===null || data.config.maxOrdersPerDay===undefined) ? '' : data.config.maxOrdersPerDay;
    document.getElementById('maxLegsPerPosition').value = data.config.maxLegsPerPosition;
    mode = data.config.mode; autoMode = data.config.autoMode; exitMode = data.config.exitMode || 'rupees';
    applyExitMode();
    updateSaveButtonLabel();
    updateInvestmentWords();
    document.querySelectorAll('#modeSeg button').forEach(b=>b.classList.toggle('active', b.dataset.mode===mode));
    document.querySelectorAll('#autoSeg button').forEach(b=>b.classList.toggle('active', (b.dataset.auto==='on')===autoMode));
  }

  if(data.lastSignal && !data.lastSignal.error){
    const s = data.lastSignal;
    document.getElementById('signalCard').style.display='block';
    const badge = document.getElementById('signalBadge');
    // "SELL" only means something when you're actually holding shares to sell. When flat, the
    // engine only ever acts on BUY (see maybeEnter on the server) — a SELL reading while flat
    // just means "bearish right now, don't buy yet", so show it as that instead of a literal SELL.
    const holding = !!data.position;
    const displayAction = (!holding && s.action==='SELL') ? 'AVOID' : s.action;
    badge.className = 'signal-badge ' + s.action; badge.textContent = displayAction;
    document.getElementById('reasonsList').innerHTML = s.reasons.map(r=>`<li>${r}</li>`).join('');
    document.getElementById('lastChecked').textContent = 'Last checked ' + new Date(s.at).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata'}) + ' IST · price ' + formatINR(s.price);
    document.getElementById('volatilityHint').textContent = data.lastVolatilityPct!=null
      ? `Current volatility (ATR): ${data.lastVolatilityPct.toFixed(2)}% of price — this scales the position size up or down.`
      : '';
    // Live price shown prominently at all times, not just inside the signal card.
    document.getElementById('livePriceBadge').textContent = 'Live: ' + formatINR(s.price);
    document.getElementById('livePriceTile').textContent = formatINR(s.price) + (s.symbol ? ' ('+s.symbol+')' : '');
  } else if(data.lastSignal && data.lastSignal.error){
    document.getElementById('lastChecked').textContent = 'Last check failed: ' + data.lastSignal.error;
    document.getElementById('livePriceBadge').textContent = 'Live: unavailable';
  }

  if(data.position){
    const p = data.position;
    document.getElementById('positionCard').style.display='block';
    document.getElementById('manualStartBtn').style.display='none';
    document.getElementById('exitBtn').style.display='block';
    const price = (data.lastSignal && !data.lastSignal.error) ? data.lastSignal.price : p.entryPrice;
    document.getElementById('posLive').textContent = formatINR(price);
    const pnl = (price-p.entryPrice)*p.qty;
    const pnlPct = (price-p.entryPrice)/p.entryPrice*100;
    const pnlEl = document.getElementById('posPnl');
    pnlEl.textContent = (pnl>=0?'+':'')+formatINR(pnl)+' ('+(pnlPct>=0?'+':'')+pnlPct.toFixed(2)+'%)';
    pnlEl.className = 'big-num ' + (pnl>=0?'pnl-pos':'pnl-neg');
    document.getElementById('posInvested').textContent = formatINR(p.investedAmount);
    document.getElementById('posQty').textContent = p.qty + ' shares';
    document.getElementById('posEntry').textContent = formatINR(p.entryPrice) + ' (avg)';
    document.getElementById('posTS').textContent = formatINR(p.target) + ' / ' + formatINR(p.stopLoss);
    document.getElementById('posSizingNote').textContent = p.sizingNote || '';
    const legs = (p.legs && p.legs.length) ? p.legs.length : 1;
    document.getElementById('posLegsTag').textContent = 'Buy leg ' + legs + (legs>1 ? ' (averaged)' : '');
  } else {
    document.getElementById('positionCard').style.display='none';
    document.getElementById('manualStartBtn').style.display='block';
    document.getElementById('exitBtn').style.display='none';
  }

  renderLog(data.tradeLog);
  renderDaySummary(data.tradeLog, data.position);
  document.getElementById('storageWarning').style.display = data.durableStorage ? 'none' : 'block';
  document.getElementById('capitalAvailable').textContent = formatINR(data.availableCapital||0);
  document.getElementById('nextMoveHint').textContent = data.nextMoveHint || 'Waiting for the first price check…';
}

refresh();
setInterval(refresh, 10000);
