"""Prepare bounded SQL Editor scripts from canonical migrations/seed.
Run after export_supabase_catalog.py. Each file is <= 400000 UTF-8 bytes.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
out=ROOT/'supabase/sql-editor';out.mkdir(exist_ok=True)
schema='begin;\n'+'\n'.join(p.read_text(encoding='utf-8') for p in sorted((ROOT/'supabase/migrations').glob('*.sql')))+'\ncommit;\n'
(out/'00-schema.sql').write_text(schema,encoding='utf-8',newline='\n')
lines=(ROOT/'supabase/seed.sql').read_text(encoding='utf-8').splitlines()
header=next(line for line in lines if line.startswith('insert into '))
rows=[line.rstrip(',') for line in lines if line.startswith('(')]
prefix='-- Catalog batch: safe to retry after success.\nbegin;\n'+header+'\n'
suffix='\non conflict(id) do nothing;\ncommit;\n'
groups=[];batch=[];size=len((prefix+suffix).encode())
for row in rows:
 extra=len(row.encode())+2
 if batch and size+extra>400000:groups.append(batch);batch=[];size=len((prefix+suffix).encode())
 if size+extra>400000:raise ValueError('Single row exceeds SQL Editor batch size')
 batch.append(row);size+=extra
if batch:groups.append(batch)
expected={'00-schema.sql'}
for number,group in enumerate(groups,1):
 name=f'{number:02}-catalog.sql';expected.add(name)
 text=prefix+',\n'.join(group)+suffix
 assert len(text.encode())<=400000
 (out/name).write_text(text,encoding='utf-8',newline='\n')
stale={p.name for p in out.glob('*.sql')}-expected
if stale:raise ValueError(f'Obsolete generated files need review: {sorted(stale)}')
assert sum(map(len,groups))==1581
for p in sorted(out.glob('*.sql')):print(p.name,p.stat().st_size)
