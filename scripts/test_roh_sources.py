import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from roh_sources import dated_title, weekly_identity, archive_mapping, refresh_roh, youtube_identity
from library import scan_archive, read, ROOT


class RohSourcesTest(unittest.TestCase):
    def test_event_year_does_not_swallow_the_following_day_month_date(self):
        self.assertEqual('2025-12-05', dated_title('ROH Final Battle 2025 05-12-25'))
        self.assertEqual('2024-04-05', dated_title('ROH Supercard of Honor 2024 05-04-24'))
        self.assertEqual('2026-05-12', dated_title('2026.05.12 ROH YouTube Special #08'))
        self.assertEqual('2026-10-05', dated_title('ROH TV Special | 10/5/2026', 'MDY'))

    def test_episode_number_is_not_part_of_the_broadcast_date(self):
        episode = weekly_identity('ROH on HonorClub Episode 1 02-03-23')
        self.assertEqual('2023-03-02', episode['date'])
        self.assertEqual('HonorClub', episode['show'])
        self.assertIsNone(weekly_identity('ROH on HonorClub Episode 88 31-10-14'))
        self.assertEqual('2024-09-19', weekly_identity('ROH on HonorClub Episode 82 19-09-24')['date'])

    def test_undated_short_or_partial_upload_does_not_enter_discovery(self):
        self.assertIsNone(weekly_identity('ROH on HonorClub #190'))
        for title in ['ROH on HonorClub 2026-10-29 highlights', 'ROH 2026-10-29 FULL MATCH']:
            self.assertIsNone(weekly_identity(title))
        data = {'metadata': {'title': 'ROH on HonorClub #190 2026-10-29'}, 'files': [
            {'name': 'recording.mp4', 'source': 'original', 'length': 900, 'size': 10000000}]}
        self.assertIsNone(archive_mapping('example', data))

    def test_full_tv_special_is_found_without_accepting_matches_or_abridged_edits(self):
        self.assertEqual('2026-10-05', youtube_identity('ROH TV Special Monday Episode | 10/5/2026', [])['date'])
        for title in ['FULL MATCH ROH TV 10/5/2026', 'FULL SHOW: Abridged ROH TV 10/5/2026',
                      'FULL SHOW: ROH Final Battle 2027']:
            self.assertIsNone(youtube_identity(title, []))

    def test_split_event_is_one_queue_entry_with_both_discs(self):
        config = {'id': 'example', 'mappedOnly': True, 'overrides': {}}
        data = {'files': []}
        for part in (1, 2):
            filename = 'All Star Extravaganza II [Disc %d].mp4' % part
            config['overrides'][filename] = {'date': '2004-12-04', 'show': 'PPV',
                'title': 'All Star Extravaganza II', 'part': part}
            data['files'].append({'name': filename, 'source': 'original', 'size': 100000000,
                                  'length': 3600})
        catalogue = {'episodes': []}
        scan_archive(catalogue, 'roh', config, data, [])
        self.assertEqual(1, len(catalogue['episodes']))
        source = catalogue['episodes'][0]['sources'][0]
        self.assertEqual(2, len(source['urls']))
        self.assertEqual(7200, source['duration'])
        catalogue = {'episodes': []}
        data['files'].pop()
        scan_archive(catalogue, 'roh', config, data, [])
        self.assertEqual([], catalogue['episodes'])

    def test_failed_discovery_item_does_not_abort_next_item(self):
        config = {'archive': {'collections': [], 'discovery': {'enabled': True, 'queries': ['query']}}}
        data = {'metadata': {'title': 'ROH on HonorClub #190 2026-10-29'},
                'files': [{'name': 'ROH.2026.10.29.mp4', 'source': 'original',
                           'size': 100000000, 'length': 4500}]}
        search = json.dumps({'response': {'docs': [{'identifier': 'broken'}, {'identifier': 'working'}]}})
        library = {'episodes': []}
        with patch('library.public_request', return_value=search), \
             patch('library.fetch_metadata', side_effect=[OSError('offline'), data]):
            report = refresh_roh(library, config)
        self.assertEqual(1, len(library['episodes']))
        self.assertEqual('skipped', report[0]['status'])
        self.assertEqual('working', config['archive']['collections'][0]['id'])

    def test_bundled_dates_parts_and_source_aliases(self):
        library = read(ROOT / 'app/src/main/assets/libraries/roh.json')
        rows = library['episodes']
        self.assertTrue(all(e['date'] >= '2023-03-02' for e in rows if e['show'] == 'HonorClub'))
        self.assertEqual(1, len([e for e in rows if e['date'] == '2025-05-09' and e['show'] == 'HonorClub']))
        self.assertTrue(any(e['date'] == '2026-08-21' and e['show'] == 'PPV' for e in rows))
        self.assertFalse(any(e['date'] == '2026-08-20' and e['show'] == 'PPV' for e in rows))
        self.assertEqual(137, len([e for e in rows if e['year'] <= 2006 and e['show'] == 'PPV']))
        urls = [s.get('url', '') for e in rows for s in e['sources']]
        self.assertFalse(any('Scrum' in url for url in urls))


if __name__ == '__main__':
    unittest.main()
