import collections,hashlib,json,math,sys
from pathlib import Path
repo=Path('/workspace/scratch/128287f34f42/zapime-corpus')
sys.path.insert(0,str(repo/'integrations/candidate/tests'))
sys.path.insert(0,str(repo/'scripts'))
from quality_evaluation import attribution,CORPORA
from candidate_evaluation import summarize

def check(ok,code):
 if not ok: raise ValueError(code)
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()

def validate(path,version,runtime):
 r=json.loads(path.read_text());corpus=json.loads((repo/f'integrations/candidate/tests/quality-corpus-{version}.json').read_text());cases=corpus['cases']
 check(r['completed'] is True and r['phone_performance_evaluated'] is False and r['energy_evaluated'] is False,'scope')
 check(r['corpus_version']==version and r['corpus_sha256']==CORPORA[version][0] and r['corpus_frozen_commit']==CORPORA[version][1],'frozen_identity')
 check(r['runtime_version']==runtime and r['model_id']=='msime-keystroke-8m-rerank-v2','runtime_or_model')
 check(r['configured_threads']==dict(intra_op=2,inter_op=1,spinning=False),'threads')
 for name,file in {'runner':'integrations/candidate/tests/quality_evaluation.py','ranking_source':'crates/zapime-core/src/ranking.rs','model_lock':'third_party/candidate-model-lock.json','dictionary_lock':'vendor/msime/resources/editions/pinyin.lock.json'}.items():
  check(r['identity'][name]==digest(repo/file),'source_'+name)
 model=json.loads((repo/'third_party/candidate-model-lock.json').read_text())
 dictionary=json.loads((repo/'vendor/msime/resources/editions/pinyin.lock.json').read_text())
 check(r['identity']['model']==model['files']['lm_step.ort']['sha256'],'model_hash')
 check(r['identity']['dictionary']==next(x['sha256'] for x in dictionary['artifacts'] if x['name']=='msime-pinyin.db'),'dictionary_hash')
 if runtime=='1.28.2':check(r['identity']['runtime']=='088f24b1fc56714d3efaaeb3ac2ee486a5d7b50ccfbb3dd26fd1a612534a05fb','official_runtime_hash')
 check(set(r['modes'])=={'empty_context','contextual'},'modes')
 out={}
 for mode,m in r['modes'].items():
  check(m['completed'] is True and m['model_executed'] is True,'mode_success')
  samples,details=m['observations'],m['details']
  check(m['cases']==len(cases)==len(samples)==len(details),'denominator')
  check([s['id'] for s in samples]==[c['id'] for c in cases]==[d['id'] for d in details],'case_ids')
  recomputed=summarize(dict(schema_version=1,synthetic=True,cases=samples))
  for key in ('cases','target_covered','target_missing','metrics','improved','regressed','unchanged','top1_rescued','top1_harmed','latency_ms'):
   check(m[key]==recomputed[key],'metric_'+key)
  outcomes=collections.Counter()
  for c,s,d in zip(cases,samples,details,strict=True):
   check(d['category']==c['category'] and d['layout']==c.get('layout','qwerty'),'classification')
   check(d['context_codepoints']==(0 if mode=='empty_context' else len(c['context'])),'context_length')
   check(d['personal_fixture']==('personal' in c),'personal_flag')
   check(0<=d['rankable_count']<=32 and 0<=d['visible_count']<=40 and d['visible_count']==len(s['baseline']),'pool_bounds')
   check(d['model_called']==(d['rankable_count']>=2),'model_called')
   check(d['outcome']==attribution(s,d['target_rankable'],d['model_called']),'attribution')
   outcomes[d['outcome']]+=1
   check(set(d['worker_memory'])=={'VmRSS','VmHWM','Threads'} and all(v>0 for v in d['worker_memory'].values()),'memory')
   if d['model_called']:check(type(d['warm_roundtrip_ms']) in (int,float) and math.isfinite(d['warm_roundtrip_ms']) and d['warm_roundtrip_ms']>=0,'warm_latency')
   else:check(d['warm_roundtrip_ms'] is None and s['latency_ms']==0,'no_model_latency')
   if 'personal' in c:
    check(not d['target_rankable'] and d['outcome']=='target_protected_or_unrankable','protected_personal')
    if c['personal']['type']=='candidate_rule':
     rank=next(i+1 for i,x in enumerate(s['baseline']) if x in s['expected']);check(rank==c['personal']['position'],'personal_position')
  check(all(m['outcome_counts'].get(name)==count for name,count in outcomes.items()) and sum(m['outcome_counts'].values())==len(cases),'outcome_partition')
  called=sum(d['model_called'] for d in details)
  check(called>0 and m['scored_cases']==called and m['native_score_calls']==called*2 and m['cache_clear_calls']==called,'call_counts')
  check(m['score_roundtrip_ms']['samples']==called==m['warm_roundtrip_ms']['samples'],'timing_denominator')
  check(set(m['by_category'])=={c['category'] for c in cases},'category_set')
  for category,g in m['by_category'].items():
   subset=[s for s,d in zip(samples,details,strict=True) if d['category']==category]
   agg=summarize(dict(schema_version=1,synthetic=True,cases=subset))
   for key in ('cases','target_missing','metrics','top1_harmed','regressed'):check(g[key]==agg[key],'category_'+key)
  out[mode]={key:m[key] for key in ('cases','target_missing','metrics','top1_harmed','regressed','outcome_counts','native_score_calls')}
 return dict(file=path.name,sha256=digest(path),runtime=runtime,corpus=version,identity=r['identity'],modes=out)

if __name__=='__main__':
 root=Path(sys.argv[1]);result=[]
 for name,version,runtime in [('quality-report.json','v2','1.24.4'),('quality-v3-report.json','v3','1.24.4'),('quality-v3-ort1282-report.json','v3','1.28.2')]:result.append(validate(root/name,version,runtime))
 for key in ('core_binary','native_probe','model','dictionary','runner','ranking_source'):check(len({r['identity'][key] for r in result})==1,'shared_identity_'+key)
 check(result[0]['identity']['runtime']==result[1]['identity']['runtime'],'default_runtime_identity')
 Path(sys.argv[2]).write_text(json.dumps(dict(strict_validated=True,source_head='75504523f8dadb5eb0cd8e147c17ef1878ed8219',reports=result,limits='Host contract/identity/metric validation only; no Android, phone performance or natural population quality acceptance'),indent=2)+'\n')
 print('PASS three exact frozen-quality reports, independent metric recomputation, source/pool/personal/call-count identities')
