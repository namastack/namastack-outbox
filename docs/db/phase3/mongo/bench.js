// usage: mongosh --eval "var SC='A',VARIANT='base'" bench.js
const c=db.getSiblingDB('outbox_'+SC).outbox_records;
const now=new Date('2026-10-30T12:00:00Z');
const owned=[];for(let p=0;p<256;p+=4)owned.push(p); // 64 of 256
const batch=10;
const keys100=[],keys1000=[];for(let i=0;i<1000;i++){const k='order-'+(1+i*7);keys1000.push(k);if(i<100)keys100.push(k);}
const strictStages=[
 {$match:{partitionNo:{$in:owned},completedAt:null}},
 {$sort:{recordKey:1,createdAt:1,_id:1}},
 {$group:{_id:'$recordKey',oldestDoc:{$first:'$$ROOT'}}},
 {$match:{'oldestDoc.status':'NEW','oldestDoc.nextRetryAt':{$lte:now}}}];
// head-of-key: outer incomplete docs in (createdAt,_id) order, correlated probe for first incomplete doc of the key, real limit
const headStages=(extra)=>[
 {$match:Object.assign({partitionNo:{$in:owned},completedAt:null,status:'NEW',nextRetryAt:{$lte:now}},extra||{})},
 {$sort:{createdAt:1,_id:1}},
 {$lookup:{from:'outbox_records',localField:'recordKey',foreignField:'recordKey',as:'head',
   pipeline:[{$match:{completedAt:null}},{$sort:{createdAt:1,_id:1}},{$limit:1},{$project:{_id:1}}]}},
 {$match:{$expr:{$eq:['$_id',{$first:'$head._id'}]}}}];
const Q={
 keys_strict_base:strictStages.concat([{$sort:{'oldestDoc.createdAt':1}},{$limit:batch},{$project:{_id:0,recordKey:'$_id'}}]),
 keys_std_base:[{$match:{partitionNo:{$in:owned},status:'NEW',nextRetryAt:{$lte:now}}},{$group:{_id:'$recordKey',minCreatedAt:{$min:'$createdAt'}}},{$sort:{minCreatedAt:1}},{$limit:batch},{$project:{_id:0,recordKey:'$_id'}}],
 due_strict_base:strictStages.concat([{$group:{_id:null,value:{$min:'$oldestDoc.nextRetryAt'}}}]),
 oldest_created_base:[{$match:{partitionNo:{$in:owned},status:'NEW'}},{$group:{_id:null,value:{$min:'$createdAt'}}}],
 keys_strict_head:headStages().concat([{$limit:batch},{$project:{_id:0,recordKey:1}}]),
 // keys that are not blocked are what the strict form returns; also needs FAILED heads excluded since status=NEW in outer
 keys_std_head:[{$match:{partitionNo:{$in:owned},status:'NEW',nextRetryAt:{$lte:now},completedAt:null}},{$sort:{createdAt:1,_id:1}},{$group:{_id:'$recordKey',m:{$first:'$createdAt'}}},{$sort:{m:1}},{$limit:batch},{$project:{_id:0,recordKey:'$_id'}}],
 due_strict_head:headStages().concat([{$group:{_id:null,value:{$min:'$nextRetryAt'}}}]),
 due_strict_head2:[{$match:{partitionNo:{$in:owned},completedAt:null,status:'NEW',nextRetryAt:{$lte:now}}},{$sort:{nextRetryAt:1,_id:1}},
   {$lookup:{from:'outbox_records',localField:'recordKey',foreignField:'recordKey',as:'head',pipeline:[{$match:{completedAt:null}},{$sort:{createdAt:1,_id:1}},{$limit:1},{$project:{_id:1}}]}},
   {$match:{$expr:{$eq:['$_id',{$first:'$head._id'}]}}},{$limit:1},{$project:{_id:0,value:'$nextRetryAt'}}],
 pages100_base:[{$match:{recordKey:{$in:keys100},status:'NEW'}},{$sort:{recordKey:1,createdAt:1,_id:1}}],
 pages100_inc:[{$match:{recordKey:{$in:keys100},status:'NEW',completedAt:null}},{$sort:{recordKey:1,createdAt:1,_id:1}}],
 pages1000_base:[{$match:{recordKey:{$in:keys1000},status:'NEW'}},{$sort:{recordKey:1,createdAt:1,_id:1}}],
 pages1000_inc:[{$match:{recordKey:{$in:keys1000},status:'NEW',completedAt:null}},{$sort:{recordKey:1,createdAt:1,_id:1}}],
 oldest_created_new:[{$match:{partitionNo:{$in:owned},status:'NEW',completedAt:null}},{$sort:{createdAt:1}},{$limit:1},{$project:{_id:0,value:'$createdAt'}}],
};
function hs(s){let x=7;for(let i=0;i<s.length;i++)x=(Math.imul(x,31)+s.charCodeAt(i))|0;return x;}
function walk(o,acc){ if(o&&typeof o==='object'){ for(const k of Object.keys(o)){ if(k==='totalKeysExamined')acc.k+=o[k]; if(k==='totalDocsExamined')acc.d+=o[k]; if(k==='executionTimeMillis'&&acc.t===null)acc.t=o[k]; if(k==='indexName'&&acc.i.indexOf(o[k])<0)acc.i.push(o[k]); if(k==='stage'&&o[k]==='COLLSCAN')acc.i.push('COLLSCAN'); if(typeof o[k]==='object')walk(o[k],acc);} } }
function run(name,pipe,hint){
  const cmd={aggregate:'outbox_records',pipeline:pipe,cursor:{}}; if(hint)cmd.hint=hint;
  const e=db.getSiblingDB('outbox_'+SC).runCommand({explain:cmd,verbosity:'executionStats'});
  const acc={k:0,d:0,t:null,i:[]}; walk(e,acc);
  const ts=[];let res;let sc0,sc1;
  for(let r=0;r<4;r++){
    if(r===3)sc0=db.serverStatus().metrics.queryExecutor;const t0=Date.now();const o={aggregate:'outbox_records',pipeline:pipe,cursor:{batchSize:100000},allowDiskUse:true};if(hint)o.hint=hint;res=db.getSiblingDB('outbox_'+SC).runCommand(o);ts.push(Date.now()-t0);}
  sc1=db.serverStatus().metrics.queryExecutor;
  ts.shift(); ts.sort((a,b)=>a-b);
  const docs=res.cursor.firstBatch;
  print([SC,VARIANT,name,'keysExam='+(sc1.scanned-sc0.scanned),'docsExam='+(sc1.scannedObjects-sc0.scannedObjects),'explainMs='+acc.t,'medianMs='+ts[1],'n='+docs.length,'idx='+acc.i.join('+'),'hash='+hs(JSON.stringify(docs))].join(' | '));
  return docs;
}
const only=(typeof ONLY!=='undefined')?ONLY.split(','):Object.keys(Q);
for(const n of only){ try{ run(n,Q[n],(typeof HINT!=='undefined')?JSON.parse(HINT):null);}catch(e){print(SC,VARIANT,n,'ERR',e.message.slice(0,200));} }
