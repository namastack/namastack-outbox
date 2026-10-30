// usage: --eval "var SC='A',SET='M1'"  ; SET: none|M0|M1|M2
const c=db.getSiblingDB('outbox_'+SC).outbox_records;
for(const n of ['inc_created_m1','inc_created_m3','inc_created_m4','inc_created_m0','inc_created_m1','inc_created_m2','fifo_pipeline_idx2'])try{c.dropIndex(n)}catch(e){}
c.createIndex({partitionNo:1,recordKey:1,createdAt:1},{name:'fifo_pipeline_idx'});c.createIndex({recordKey:1,completedAt:1,createdAt:1},{name:'record_key_completed_created_idx'});
if(SET==='M0')c.createIndex({createdAt:1,_id:1},{name:'inc_created_m0',partialFilterExpression:{completedAt:null}});
if(SET==='M1')c.createIndex({createdAt:1,_id:1,partitionNo:1,status:1,nextRetryAt:1,recordKey:1},{name:'inc_created_m1',partialFilterExpression:{completedAt:null}});
if(SET==='M2')c.createIndex({createdAt:1,_id:1,partitionNo:1,status:1,nextRetryAt:1,recordKey:1},{name:'inc_created_m2',partialFilterExpression:{status:{$in:['NEW','FAILED']}}});
if(SET==='M3')c.createIndex({partitionNo:1,recordKey:1,createdAt:1,_id:1},{name:'inc_created_m3',partialFilterExpression:{completedAt:null}});
if(SET==='M4'||SET==='M1+M4'||SET==='M1+M4-drop'){ if(SET!=='M4')c.createIndex({createdAt:1,_id:1,partitionNo:1,status:1,nextRetryAt:1,recordKey:1},{name:'inc_created_m1',partialFilterExpression:{completedAt:null}});
 c.createIndex({recordKey:1,createdAt:1,_id:1},{name:'inc_created_m4',partialFilterExpression:{completedAt:null}});}
if(SET==='M1+M4-drop'){c.dropIndex('record_key_completed_created_idx');c.dropIndex('fifo_pipeline_idx');}
print(SC,SET,c.getIndexes().map(i=>i.name).join(','), 'size', JSON.stringify(c.stats().indexSizes));
