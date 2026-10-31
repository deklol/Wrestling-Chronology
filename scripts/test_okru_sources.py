import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parent))
from okru_sources import Page, refresh_okru, _pages


class OkruTests(unittest.TestCase):
    def test_metadata_and_owner_are_read_without_saving_signed_streams(self):
        data=json.dumps({'flashvars':{'metadata':{'provider':'UPLOADED_ODKL','movie':{'title':'Dynamite'}}}})
        page=Page('<a class="movie_author_link" href="/profile/579391530351/video/all"></a><div data-options=\''+data+'\'></div>')
        self.assertEqual('579391530351',page.owner)
        self.assertEqual('Dynamite',page.metadata[0]['movie']['title'])

    def test_collection_failure_preserves_all_existing_episodes(self):
        library={'episodes':[{'id':'existing','sources':[{'type':'okru','videoId':'123456'}]}]}
        before=json.dumps(library)
        config={'okru':{'enabled':True,'videos':[], 'collections':[{
            'url':'https://ok.ru/profile/579391530351/video/all','owner':'579391530351'}]}}
        _pages.clear()
        with patch('library.public_request',side_effect=OSError('temporary provider failure')):
            report=refresh_okru(library,'aew',config)
        self.assertEqual(before,json.dumps(library))
        self.assertEqual('skipped',report[0]['status'])


if __name__=='__main__':unittest.main()
