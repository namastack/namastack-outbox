const c=db.getSiblingDB('outbox_A').outbox_records;
const tries={
 eqNull:{completedAt:null},
 existsFalse:{completedAt:{$exists:false}},
 typeNull:{completedAt:{$type:'null'}},
 statusIn:{status:{$in:['NEW','FAILED']}},
 statusEqNew:{status:'NEW'},
 statusNe:{status:{$ne:'COMPLETED'}},
 statusNin:{status:{$nin:['COMPLETED']}},
 or:{$or:[{status:'NEW'},{status:'FAILED'}]},
 andEq:{$and:[{status:{$in:['NEW','FAILED']}}]},
 gtStatus:{status:{$lt:'COMPLETED'}}, // lexicographic: COMPLETED < FAILED < NEW
 gtCompleted:{status:{$gt:'COMPLETED'}},
};
for(const [k,v] of Object.entries(tries)){
  try{c.createIndex({createdAt:1,_id:1},{name:'pf_'+k,partialFilterExpression:v});print(k,'OK');c.dropIndex('pf_'+k);}
  catch(e){print(k,'ERR',e.codeName,e.message.slice(0,90));}
}
