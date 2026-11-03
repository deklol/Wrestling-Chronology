import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parent))
from okru_sources import Page, refresh_okru, _pages


class OkruTests(unittest.TestCase):
    def test_profile_cards_with_script_navigation_are_discovered(self):
        page = Page('''<a class="video-card_n ellip" href="#" title="Raw 2026-11-02"
                    onclick="OK.VideoPlayer.openMovie(&quot;16275297405518&quot;,&quot;profile&quot;)">Raw</a>
                    <a href="/video/123456" title="SmackDown 2026-10-30">SmackDown</a>
                    <a href="#" onclick="unrelated(999999)" title="Not a video">Other</a>''')
        self.assertEqual({'16275297405518': 'Raw 2026-11-02', '123456': 'SmackDown 2026-10-30'}, page.links)

    def discovery(self, listing, title, library=None, company='wwe'):
        owner = '579391530351'
        feed = 'https://ok.ru/profile/' + owner + '/video/all'
        config = {'okru': {'enabled': True, 'videos': [], 'collections': [{'url': feed, 'owner': owner}]}}
        library = library if library is not None else {'episodes': []}
        pages = {feed: SimpleNamespace(links={'123456': listing}),
                 'https://ok.ru/video/123456': SimpleNamespace(owner=owner, metadata=[{
                     'movie': {'title': title, 'duration': 7200}, 'hlsManifestUrl': 'https://example.invalid/master.m3u8'}])}
        with patch('okru_sources.page', side_effect=lambda url: pages[url]) as fetch, \
             patch('library.read', return_value={company: []}):
            report = refresh_okru(library, company, config)
        return library, report, fetch.call_count

    def test_new_raw_is_added_under_its_broadcast_date(self):
        library, report, _ = self.discovery('Raw 2026-11-02', 'Raw 2026-11-02')
        self.assertEqual([], report[0]['skipped'])
        self.assertEqual('Raw', library['episodes'][0]['show'])
        self.assertEqual('2026-11-02', library['episodes'][0]['date'])

    def test_nxt_profile_upload_stays_out_of_wwe(self):
        title = 'WWE.NXT.2026.10.27.CW.720p'
        wwe, _, _ = self.discovery(title, title)
        nxt, report, _ = self.discovery(title, title, company='nxt')
        self.assertEqual([], wwe['episodes'])
        self.assertEqual('nxt:2026-10-27:nxt', nxt['episodes'][0]['id'])
        self.assertEqual([], report[0]['skipped'])

    def test_existing_catalogue_source_skips_detail_request(self):
        existing = {'episodes': [{'id': 'existing', 'sources': [{'type': 'okru', 'videoId': '123456'}]}]}
        _, report, requests = self.discovery('Raw 2026-11-02', 'Raw 2026-11-02', existing)
        self.assertEqual(1, requests)
        self.assertEqual(0, report[0]['new_recordings'])

    def test_conflicting_programme_or_date_is_held(self):
        for title in ('SmackDown 2026-11-02', 'Raw 2026-11-01', 'Raw documentary 2026-11-02'):
            with self.subTest(title=title):
                library, report, _ = self.discovery('Raw 2026-11-02', title)
                self.assertEqual([], library['episodes'])
                self.assertEqual(1, len(report[0]['skipped']))

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
