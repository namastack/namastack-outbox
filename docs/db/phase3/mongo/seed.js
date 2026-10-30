// usage: mongosh --eval "var SC='A'" seed.js   -> database outbox_<SC>, 10k keys x 20 = 200k docs
function h(s){let x=0;for(let i=0;i<s.length;i++){x=(Math.imul(x,31)+s.charCodeAt(i))|0;}return Math.abs(x)%256;}
const d=db.getSiblingDB('outbox_'+SC); d.dropDatabase();
const c=d.outbox_records;
const now=new Date('2026-10-30T12:00:00Z').getTime();
const newFrom = SC==='A'?18:13, newKeys = SC==='A'?400:5000, failN = SC==='A'?17:12;
const pay='x'.repeat(500);
let batch=[];
for(let n=1;n<=20;n++)for(let k=1;k<=10000;k++){
  const isNew=k<=newKeys&&n>=newFrom, isFail=k<=40&&n===failN;
  const ts=new Date(now-30*86400000+(n*10000+k)*50);
  const doc={_id:'id-'+k+'-'+n,status:isNew?'NEW':isFail?'FAILED':'COMPLETED',recordKey:'order-'+k,recordType:'com.example.OrderEvent',payload:pay,partitionNo:h('order-'+k),createdAt:ts,failureCount:isFail?5:0,nextRetryAt:(k<=20&&isNew)?new Date(now+3600000):ts,handlerId:'com.example.Handler#handle'};
  if(!isNew&&!isFail)doc.completedAt=new Date(ts.getTime()+1000);
  batch.push(doc); if(batch.length===5000){c.insertMany(batch);batch=[];}
}
if(batch.length)c.insertMany(batch);
// current indexes
c.createIndex({recordKey:1,createdAt:1},{name:'record_key_created_idx'});
c.createIndex({partitionNo:1,status:1,nextRetryAt:1},{name:'partition_status_retry_idx'});
c.createIndex({status:1,nextRetryAt:1},{name:'status_retry_idx'});
c.createIndex({recordKey:1,completedAt:1,createdAt:1},{name:'record_key_completed_created_idx'});
c.createIndex({partitionNo:1,recordKey:1,createdAt:1},{name:'fifo_pipeline_idx'});
c.createIndex({status:1},{name:'status'});
print(SC, c.countDocuments(), 'NEW',c.countDocuments({status:'NEW'}),'FAILED',c.countDocuments({status:'FAILED'}));
