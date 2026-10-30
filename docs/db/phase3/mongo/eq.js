// value equivalence of base vs rewritten (due) and key-list equality, after current index set
const d=db.getSiblingDB('outbox_'+SC), c=d.outbox_records;
const now=new Date('2026-10-30T12:00:00Z'); const owned=[];for(let p=0;p<256;p+=4)owned.push(p);
const strict=[{$match:{partitionNo:{$in:owned},completedAt:null}},{$sort:{recordKey:1,createdAt:1,_id:1}},{$group:{_id:'$recordKey',oldestDoc:{$first:'$$ROOT'}}},{$match:{'oldestDoc.status':'NEW','oldestDoc.nextRetryAt':{$lte:now}}}];
const probe={$lookup:{from:'outbox_records',localField:'recordKey',foreignField:'recordKey',as:'head',pipeline:[{$match:{completedAt:null}},{$sort:{createdAt:1,_id:1}},{$limit:1},{$project:{_id:1}}]}};
const hm={$match:{$expr:{$eq:['$_id',{$first:'$head._id'}]}}};
const outer={$match:{partitionNo:{$in:owned},completedAt:null,status:'NEW',nextRetryAt:{$lte:now}}};
const full=c.aggregate(strict.concat([{$sort:{'oldestDoc.createdAt':1}},{$project:{_id:1}}])).toArray().map(x=>x._id);
const headAll=c.aggregate([outer,{$sort:{createdAt:1,_id:1}},probe,hm,{$project:{recordKey:1}}]).toArray().map(x=>x.recordKey);
const same=JSON.stringify(full)===JSON.stringify(headAll);
for(const b of [1,10,100,1000]){const a=full.slice(0,b),h=c.aggregate([outer,{$sort:{createdAt:1,_id:1}},probe,hm,{$limit:b},{$project:{recordKey:1}}]).toArray().map(x=>x.recordKey);
 print(SC,'limit',b,JSON.stringify(a)===JSON.stringify(h)?'SAME ORDER':'DIFF (tie-order?)', a.length, h.length);}
print(SC,'full ready-key list equal (ordered):',same,'count',full.length,headAll.length);
const dueA=c.aggregate(strict.concat([{$group:{_id:null,v:{$min:'$oldestDoc.nextRetryAt'}}}])).toArray()[0];
const dueB=c.aggregate([outer,{$sort:{nextRetryAt:1,_id:1}},probe,hm,{$limit:1},{$project:{v:'$nextRetryAt'}}]).toArray()[0];
print(SC,'due base',dueA&&dueA.v,'head',dueB&&dueB.v);
