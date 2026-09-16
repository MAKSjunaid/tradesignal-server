let mode = 'intraday', autoMode = false;

function istNow(){ return new Date(new Date().toLocaleString('en-US',{timeZone:'Asia/Kolkata'})); }
function tickClock(){
  const n = istNow();
  document.getElementById('istClock').textContent = String(n.getHours()).padStart(2,'0')+':'+String(n.getMinutes()).padStart(2,'0')+':'+String(n.getSeconds()).padStart(2,'0')+' IST';
}
setInterval(tickClock,1000); tickClock();

document.getElementById('modeSeg').addEventListener('click', e=>{
  const b=e.target.closest('button'); if(!b) return;
  mode=b.dataset.mode;
  document.querySelectorAll('#modeSeg button').forEach(x=>x.classList.toggle('active',x===b));
});
document.getElementById('autoSeg').addEventListener('click', e=>{
  const b=e.target.closest('button'); if(!b) return;
  autoMode = b.dataset.auto==='on';
  document.querySelectorAll('#autoSeg button').forEach(x=>x.classList.toggle('active',x===b));
});

document.getElementById('saveBtn').addEventListener('click', async ()=>{
  const errBox=document.getElementById('errBox'); errBox.style.display='none';
  const body = {
    symbol: document.getElementById('symbol').value,
    suffix: document.getElementById('exchange').value,
    investment: parseFloat(document.getElementById('investment').value)||0,
    riskPerTradePct: parseFloat(document.getElementById('riskPerTradePct').value)||1,
    maxOrderPct: parseFloat(document.getElementById('maxOrderPct').value)||50,
    mode, autoMode,
    targetPct: parseFloat(document.getElementById('targetPct').value)||0,
    stopPct: parseFloat(document.getElementById('stopPct').value)||0,
  };
  try{
    const r = await fetch('/api/config', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)});
    if(!r.ok) throw new Error('save failed');
    refresh();
  }catch(e){ errBox.textContent='Could not save setup.'; errBox.style.display='block'; }
});

document.getElementById('manualStartBtn').addEventListener('click', async ()=>{
  const errBox=document.getElementById('errBox'); errBox.style.display='none';
  try{
    const r = await fetch('/api/start', {method:'POST'});
    const data = await r.json();
    if(!data.ok){ errBox.textContent = data.error; errBox.style.display='block'; return; }
    refresh();
  }catch(e){ errBox.textContent='Could not start trade.'; errBox.style.display='block'; }
});
document.getElementById('exitBtn').addEventListener('click', async ()=>{
  await fetch('/api/exit', {method:'POST'});
  refresh();
});
document.getElementById('resetBtn').addEventListener('click', async ()=>{
  if(!confirm('This closes any open trade, clears the entire trade log, and resets the symbol/investment/target/stop back to blank. This cannot be undone. Continue?')) return;
  await fetch('/api/reset', {method:'POST'});
  formTouched = false;
  refresh();
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
      html += '<table><thead><tr><th>Symbol</th><th>Mode</th><th>Invested ₹</th><th>Buy time / price</th><th>Sell time / price</th><th>Qty</th><th>P&amp;L</th><th>Reason</th></tr></thead><tbody>';
      lastLabel=label;
    }
    const cls = t.pnl>=0?'pnl-pos':'pnl-neg';
    const buyTime = new Date(t.entryTime).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit'});
    const sellTime = new Date(t.exitTime).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata',hour:'2-digit',minute:'2-digit'});
    html += `<tr>
      <td data-label="Symbol">${t.symbol}</td>
      <td data-label="Mode">${t.mode}</td>
      <td data-label="Invested">₹${t.investedAmount.toFixed(2)}</td>
      <td data-label="Buy">${buyTime} · ₹${t.entryPrice.toFixed(2)}</td>
      <td data-label="Sell">${sellTime} · ₹${t.exitPrice.toFixed(2)}</td>
      <td data-label="Qty">${t.qty}</td>
      <td data-label="P&amp;L" class="${cls}">${t.pnl>=0?'+':''}₹${t.pnl.toFixed(2)} (${t.pnlPct.toFixed(2)}%)</td>
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
  el.textContent = (total>=0?'+':'')+'₹'+total.toFixed(2);
  el.className = 'v ' + (total>=0?'pnl-pos':'pnl-neg');
  document.getElementById('dayTrades').textContent = todays.length;
  const wins = todays.filter(t=>t.pnl>0).length;
  document.getElementById('dayWinRate').textContent = todays.length ? Math.round(wins/todays.length*100)+'%' : '—';

  // Capital deployed today = money used by today's closed trades, plus any position still open from today.
  let invested = todays.reduce((a,t)=>a+t.investedAmount,0);
  if(position && dateLabel(position.entryTime)==='Today') invested += position.investedAmount;
  document.getElementById('dayInvested').textContent = '₹'+invested.toFixed(2);
}

let formTouched = false;
['symbol','investment','riskPerTradePct','maxOrderPct','targetPct','stopPct'].forEach(id=>{
  document.getElementById(id).addEventListener('input', ()=>formTouched=true);
});

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
    mode = data.config.mode; autoMode = data.config.autoMode;
    document.querySelectorAll('#modeSeg button').forEach(b=>b.classList.toggle('active', b.dataset.mode===mode));
    document.querySelectorAll('#autoSeg button').forEach(b=>b.classList.toggle('active', (b.dataset.auto==='on')===autoMode));
  }

  if(data.lastSignal && !data.lastSignal.error){
    const s = data.lastSignal;
    document.getElementById('signalCard').style.display='block';
    const badge = document.getElementById('signalBadge');
    badge.className = 'signal-badge ' + s.action; badge.textContent = s.action;
    document.getElementById('reasonsList').innerHTML = s.reasons.map(r=>`<li>${r}</li>`).join('');
    document.getElementById('lastChecked').textContent = 'Last checked ' + new Date(s.at).toLocaleTimeString('en-IN',{timeZone:'Asia/Kolkata'}) + ' IST · price ₹' + s.price.toFixed(2);
    document.getElementById('volatilityHint').textContent = data.lastVolatilityPct!=null
      ? `Current volatility (ATR): ${data.lastVolatilityPct.toFixed(2)}% of price — this scales the position size up or down.`
      : '';
  } else if(data.lastSignal && data.lastSignal.error){
    document.getElementById('lastChecked').textContent = 'Last check failed: ' + data.lastSignal.error;
  }

  if(data.position){
    document.getElementById('positionCard').style.display='block';
    document.getElementById('manualStartBtn').style.display='none';
    document.getElementById('exitBtn').style.display='block';
    const price = (data.lastSignal && !data.lastSignal.error) ? data.lastSignal.price : data.position.entryPrice;
    document.getElementById('posLive').textContent = '₹'+price.toFixed(2);
    const pnl = (price-data.position.entryPrice)*data.position.qty;
    const pnlPct = (price-data.position.entryPrice)/data.position.entryPrice*100;
    const pnlEl = document.getElementById('posPnl');
    pnlEl.textContent = (pnl>=0?'+':'')+'₹'+pnl.toFixed(2)+' ('+(pnlPct>=0?'+':'')+pnlPct.toFixed(2)+'%)';
    pnlEl.className = 'big-num ' + (pnl>=0?'pnl-pos':'pnl-neg');
    document.getElementById('posInvested').textContent = '₹'+data.position.investedAmount.toFixed(2);
    document.getElementById('posEntry').textContent = '₹'+data.position.entryPrice.toFixed(2)+' × '+data.position.qty;
    document.getElementById('posTarget').textContent = '₹'+data.position.target.toFixed(2);
    document.getElementById('posStop').textContent = '₹'+data.position.stopLoss.toFixed(2);
    document.getElementById('posSizingNote').textContent = data.position.sizingNote || '';
  } else {
    document.getElementById('positionCard').style.display='none';
    document.getElementById('manualStartBtn').style.display='block';
    document.getElementById('exitBtn').style.display='none';
  }

  renderLog(data.tradeLog);
  renderDaySummary(data.tradeLog, data.position);
  document.getElementById('capitalAvailable').textContent = '₹'+(data.availableCapital||0).toFixed(2);
  document.getElementById('nextMoveHint').textContent = data.nextMoveHint || 'Waiting for the first price check…';
}

refresh();
setInterval(refresh, 10000);
