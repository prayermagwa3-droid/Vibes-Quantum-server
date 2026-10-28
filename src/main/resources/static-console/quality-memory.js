(() => {
 const $ = id => document.getElementById(id);
 let previewId = null;
 const adminKey = () => $('gatewayAdminKey')?.value || '';
 async function api(path, options={}) {
   const response = await fetch(path,{...options,headers:{'Content-Type':'application/json','X-Vibes-Gateway-Key':adminKey(),...(options.headers||{})}});
   const data = await response.json().catch(()=>({error:`HTTP ${response.status}`}));
   if(!response.ok) throw new Error(data.error || `Request failed (${response.status})`);
   return data;
 }
 const status=(id,msg)=>{if($(id)) $(id).textContent=msg;};
 $('qualityEvalForm')?.addEventListener('submit',async e=>{
   e.preventDefault(); const f=new FormData(e.currentTarget);
   const request={engineVersion:f.get('engineVersion'),datasetVersion:f.get('datasetVersion'),configurationId:f.get('configurationId'),sampleSize:Number(f.get('sampleSize')),candidateScore:Number(f.get('candidateScore')),baselineScore:f.get('baselineScore')===''?null:Number(f.get('baselineScore')),criticalFailures:Number(f.get('criticalFailures')||0),avgLatencyMs:Number(f.get('avgLatencyMs')||0),providerFailures:Number(f.get('providerFailures')||0),estimatedCost:Number(f.get('estimatedCost')||0),privacySecurityPassed:f.has('privacySecurityPassed'),providerIds:[],cases:[{id:'metadata-placeholder',category:'metadata',prompt:'',reference:'',critical:Number(f.get('criticalFailures')||0)>0}]};
   status('qualityEvalStatus','Submitting evaluation metadata…');
   try {const r=await api('/api/v1/quality/evaluate',{method:'POST',body:JSON.stringify(request)});status('qualityEvalStatus',`${r.promoted?'Gate passed':'Gate blocked'} · ${r.gateReasons.join('; ')||'No configured gate failure'}`); await refreshHistory();}
   catch(err){status('qualityEvalStatus',err.message);}
 });
 async function refreshHistory(){try{const [runs,reviews]=await Promise.all([api('/api/v1/quality/history'),api('/api/v1/quality/reviews')]);$('qualityHistoryOutput').innerHTML=runs.length?runs.map(r=>`<div class="quality-row"><strong>${escapeHtml(r.engineVersion)}</strong><span>${escapeHtml(r.datasetVersion)}</span><span>Score ${(r.candidateScore*100).toFixed(1)}%</span><span>${r.promoted?'GATE PASSED':'BLOCKED'}</span><small>${escapeHtml((r.gateReasons||[]).join('; ')||r.createdAt)}</small></div>`).join(''):'No runs yet.';$('qualityReviewOutput').innerHTML=reviews.length?reviews.map(r=>`<div class="quality-row"><strong>${escapeHtml(r.kind)}</strong><span>${escapeHtml(r.subject)}</span><small>${escapeHtml(r.reason)}</small></div>`).join(''):'No pending reviews.';}catch(err){$('qualityHistoryOutput').textContent=err.message;}}
 $('refreshQualityHistory')?.addEventListener('click',refreshHistory);
 $('memoryPreviewButton')?.addEventListener('click',async()=>{status('memoryStatus','Creating non-destructive preview…');try{const p=await api('/api/v1/memory-lifecycle/preview',{method:'POST',body:JSON.stringify({storageQuotaRecords:Number($('memoryQuota').value||10000)})});previewId=p.previewId;$('memoryPreviewOutput').textContent=`Preview ${p.previewId}: keep ${p.kept}, refresh due ${p.refreshDue}, quarantine ${p.quarantined}, duplicate suggestions ${p.duplicatesSuggested}, soft-delete candidates ${p.softDeleteCandidates}. ${p.reasons.join('. ')}`;$('memoryMaintainButton').disabled=false;status('memoryStatus','Preview ready. Review counts before confirming.');}catch(err){status('memoryStatus',err.message);}});
 $('memoryMaintainButton')?.addEventListener('click',async()=>{if(!previewId)return; if(!confirm('Run reversible maintenance from this preview? Approved facts will not be deleted for age or inactivity.'))return;try{const r=await api('/api/v1/memory-lifecycle/maintain',{method:'POST',body:JSON.stringify({confirmPreviewId:previewId,confirmSoftDelete:false,storageQuotaRecords:Number($('memoryQuota').value||10000)})});status('memoryStatus',`Complete: kept ${r.kept}, quarantined ${r.quarantined}, soft-deleted ${r.softDeleted}.`);$('memoryMaintainButton').disabled=true;}catch(err){status('memoryStatus',err.message);}});
 function escapeHtml(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
})();
