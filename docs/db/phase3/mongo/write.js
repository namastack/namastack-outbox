// usage: --eval "var SET='base'|'prop'"  : 30k inserts (insertMany 1000) then 30k updateOne to COMPLETED, ms
const d=db.getSiblingDB('wc_'+SET);d.dropDatabase();const c=d.outbox_records;
c.createIndex({recordKey:1,createdAt:1},{name:'record_key_created_idx'});c.createIndex({partitionNo:1,status:1,nextRetryAt:1});c.createIndex({status:1,nextRetryAt:1});c.createIndex({status:1});
if(SET==='base'){c.createIndex({recordKey:1,completedAt:1,createdAt:1});c.createIndex({partitionNo:1,recordKey:1,createdAt:1});}
else{c.createIndex({createdAt:1,_id:1,partitionNo:1,status:1,nextRetryAt:1,recordKey:1},{partialFilterExpression:{completedAt:null}});c.createIndex({recordKey:1,createdAt:1,_id:1},{partialFilterExpression:{completedAt:null}});}
const pay='x'.repeat(500);const N=30000;let t0=Date.now();
for(let i=0;i<N;i+=1000){const b=[];for(let j=i;j<i+1000;j++)b.push({_id:'w'+j,status:'NEW',recordKey:'k'+(j%5000),recordType:'t',payload:pay,partitionNo:j%256,createdAt:new Date(1e12+j*50),failureCount:0,nextRetryAt:new Date(1e12+j*50),handlerId:'h'});c.insertMany(b);}
const ins=Date.now()-t0;t0=Date.now();
for(let j=0;j<N;j++)c.updateOne({_id:'w'+j},{$set:{status:'COMPLETED',completedAt:new Date(1e12+j*50+1000)}});
print(SET,'insert ms',ins,'update ms',Date.now()-t0,'indexSizeKB',Math.round(c.stats().totalIndexSize/1024));
