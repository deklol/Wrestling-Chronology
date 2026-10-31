import copy
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from event_identity import event_part, event_title, apply_source_corrections
from library import add_record
from okru_sources import refresh_okru


class EventIdentityTests(unittest.TestCase):
    def test_main_event_with_preshow_included_is_still_main(self):
        self.assertEqual('main', event_part('Revolution.2026.PPV.with.Zero.Hour'))
        self.assertEqual('preshow', event_part('Revolution.2026.Zero.Hour'))
        self.assertEqual('tailgate', event_part('Saturday.Tailgate.Brawl.WrestleDream.2026.10.17'))
        self.assertEqual('WrestleDream — Saturday Tailgate Brawl',
                         event_title('WrestleDream', 'tailgate', 'upload filename'))

    def test_discovery_keeps_all_three_broadcasts_separate_in_any_order(self):
        uploads = [('111111', 'WrestleDream.PPV.2026.', 15288),
                   ('222222', 'Saturday.Tailgate.Brawl.WrestleDream.2026.10.17.', 2690),
                   ('333333', 'WrestleDream.2026.Buy.In.', 3546)]
        for order in (uploads, list(reversed(uploads))):
            with self.subTest(order=order[0][0]):
                config = {'okru': {'enabled': True, 'videos': [], 'collections': [
                    {'owner': '579391530351', 'url': 'https://ok.ru/profile/579391530351/video/all'}]}}
                library = {'episodes': []}
                pages = {'https://ok.ru/profile/579391530351/video/all': SimpleNamespace(links={i: t for i, t, _ in order})}
                for identifier, title, duration in order:
                    pages['https://ok.ru/video/' + identifier] = SimpleNamespace(owner='579391530351', metadata=[
                        {'movie': {'title': title, 'duration': duration}, 'hlsManifestUrl': 'https://example.invalid/master.m3u8'}])
                with patch('okru_sources.page', side_effect=lambda url: pages[url]), patch('library.read', return_value={
                    'aew': [{'name': 'WrestleDream', 'date': '2026-10-17'}]}):
                    report = refresh_okru(library, 'aew', config)
                self.assertEqual([], report[0]['skipped'])
                self.assertEqual(3, len(library['episodes']))
                main = next(r for r in library['episodes'] if r['title'] == 'WrestleDream')
                self.assertEqual(15288, main['duration'])
                self.assertEqual(['111111'], [s['videoId'] for s in main['sources']])
                self.assertEqual(3, len({v['episodeId'] for v in config['okru']['videos']}))

    def test_corrections_preserve_unrelated_sources_and_need_a_real_destination(self):
        full = {'type': 'okru', 'videoId': '111111'}
        short = {'type': 'okru', 'videoId': '222222'}
        library = {'episodes': [{'id': 'tailgate', 'sources': [full, short]},
                                {'id': 'main', 'sources': [copy.deepcopy(full)]}],
                   'sourceAssignments': {'okru:111111': 'main', 'okru:222222': 'missing'}}
        for _ in range(2):
            apply_source_corrections(library, {})
        self.assertEqual([short], library['episodes'][0]['sources'])
        self.assertEqual([full], library['episodes'][1]['sources'])

    def test_existing_bad_recording_mapping_is_reported_not_reused(self):
        source = {'type': 'okru', 'videoId': '111111'}
        library = {'episodes': [{'id': 'old', 'date': '2026-10-17', 'show': 'PPV',
                                'title': 'WrestleDream — Saturday Tailgate Brawl', 'sources': [source]}]}
        with self.assertRaises(ValueError):
            add_record(library, 'aew', '2026-10-17', 'PPV', 'WrestleDream', source)


if __name__ == '__main__':
    unittest.main()
