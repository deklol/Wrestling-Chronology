import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from njpw_sources import archive_rows, merge_rows


class HistoricalArchiveTest(unittest.TestCase):
    def metadata(self, files):
        return {'metadata': {'identifier': '1994-tournament', 'title': 'NJPW 1994'},
                'files': [{'name': name, 'length': length} for name, length in files]}

    def config(self, names, date='1994-04-16', show='PPV'):
        return {'archiveOverrides': {'1994-tournament': {'programmes': [
            {'date': date, 'show': show, 'title': 'Super J-Cup', 'files': names}]}}}

    def test_reviewed_matches_become_one_ordered_event_not_separate_episodes(self):
        data = self.metadata([('b-opening.mp4', 30), ('c-match.mp4', 3600), ('z-final.mp4', 3600)])
        rows = archive_rows(data, self.config(['b-opening.mp4', 'c-match.mp4', 'z-final.mp4']))
        self.assertEqual(1, len(rows))
        self.assertEqual(7230, rows[0]['duration'])
        self.assertEqual(3, len(rows[0]['sources'][0]['urls']))
        self.assertTrue(rows[0]['sources'][0]['urls'][-1].endswith('z-final.mp4'))
        self.assertEqual(1, len(merge_rows(rows, rows)))

    def test_missing_part_never_publishes_a_partial_event(self):
        self.assertEqual([], archive_rows(self.metadata([('first.mp4', 7000)]), self.config(['first.mp4', 'missing.mp4'])))

    def test_explicit_broadcast_date_overrides_recording_filename(self):
        rows = archive_rows(self.metadata([('1994.7.4.part2.mp4', 2684)]),
                            self.config(['1994.7.4.part2.mp4'], '1994-07-02', 'World Pro-Wrestling'))
        self.assertEqual('1994-07-02', rows[0]['date'])
        self.assertIn('url', rows[0]['sources'][0])

    def test_cutoff_and_private_files(self):
        data = self.metadata([('event.mp4', 7000)])
        self.assertEqual([], archive_rows(data, self.config(['event.mp4'], '1989-12-31')))
        data['files'][0]['private'] = True
        self.assertEqual([], archive_rows(data, self.config(['event.mp4'])))


if __name__ == '__main__': unittest.main()
