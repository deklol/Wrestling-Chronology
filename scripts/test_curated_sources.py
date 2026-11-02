from pathlib import Path
import json,sys,unittest
sys.path.insert(0,str(Path(__file__).resolve().parent))
from curated_sources import adapt,refresh_curated
from unittest.mock import patch
import tempfile
import copy

ROOT=Path(__file__).resolve().parents[1]
class CuratedTests(unittest.TestCase):
    def test_existing_libraries_preserve_ids_and_all_provider_alternatives(self):
        for company in ('njpw','nxt'):
            original=json.loads((ROOT/'catalogue'/f'{company}.json').read_bytes())
            library,config=adapt(company,original['catalogue'],original['sources'])
            self.assertEqual(original['catalogue'],library)
            self.assertEqual(original['sources'],config)
    def test_failed_provider_does_not_remove_existing_sources(self):
        company='nxt';pack=json.loads((ROOT/'catalogue/nxt.json').read_bytes())
        previous=pack['catalogue'];source=pack['sources']
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'sources/discovery';target.mkdir(parents=True)
            (target/'nxt.json').write_text('{}')
            with patch('nxt_sources.refresh',return_value=(previous['episodes'],0,['Archive unavailable'])):
                library,config,report=refresh_curated(folder,company,previous,source)
        self.assertEqual(previous,library)
        self.assertEqual('skipped',report[-1]['status'])
    def test_unverified_new_recording_does_not_block_other_sources(self):
        pack=json.loads((ROOT/'catalogue/nxt.json').read_bytes())
        previous=pack['catalogue'];rows=copy.deepcopy(previous['episodes'])
        rows[0]['sources'].append({'type':'youtube','videoId':'unknown1234','duration':3600})
        rows[0]['sources'].append({'type':'archive','url':'https://archive.org/download/reviewed-new/show.mp4','duration':3600})
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'sources/discovery';target.mkdir(parents=True)
            (target/'nxt.json').write_text('{}')
            with patch('nxt_sources.refresh',return_value=(rows,1,[])):
                library,config,report=refresh_curated(folder,'nxt',previous,pack['sources'])
        self.assertEqual(len(previous['episodes']),len(library['episodes']))
        self.assertEqual(len(previous['episodes'][0]['sources'])+1,len(library['episodes'][0]['sources']))
        self.assertIn('uploader identity not verified',report[-1]['reason'])
if __name__=='__main__':unittest.main()
