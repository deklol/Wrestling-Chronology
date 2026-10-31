import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from aew_sources import CHANNEL, candidate, identity
from library import add_record


class PreshowTests(unittest.TestCase):
    def test_short_complete_preshow_and_matches_in_full_broadcast_title(self):
        title = 'EDDIE KINGSTON vs JUN AKIYAMA | AEW Zero Hour: Full Gear Pre Show, 11/19/22'
        result = identity(title, 3627, {'channelId': CHANNEL, 'publishDate': '2022-11-19T17:00:00-07:00'}, [])
        self.assertEqual('PPV', result['show'])
        self.assertEqual(-1, result['eventOrder'])
        self.assertIsNone(identity(title, 500, {'channelId': CHANNEL}, []))
        self.assertIsNone(identity(title, 3627, {'channelId': 'wrong'}, []))

    def test_non_aew_and_individual_matches_excluded(self):
        self.assertFalse(candidate('Zero Hour: Ring of Honor Final Battle Pre Show'))
        self.assertFalse(candidate('Big Boom AJ brings the BOOM! | AEW Full Gear'))
        self.assertFalse(candidate('ROH World Title match | Dynamite Buy In'))

    def test_alternate_source_merges_without_absorbing_main_ppv(self):
        main = {'id': 'main', 'date': '2026-05-24', 'show': 'PPV', 'title': 'Double or Nothing', 'sources': []}
        pre = {'id': 'original-pre', 'date': '2026-05-24', 'show': 'PPV', 'title': 'AEW Double or Nothing Buy In',
               'eventPart': 'preshow', 'sources': []}
        library = {'episodes': [main, pre]}
        source = {'type': 'youtube', 'videoId': 'Sz2Hy-JOitM', 'eventPart': 'preshow'}
        for _ in range(2):
            add_record(library, 'aew', '2026-05-24', 'PPV', 'Double or Nothing — Buy In', source)
        self.assertEqual(2, len(library['episodes']))
        self.assertEqual([], main['sources'])
        self.assertEqual([source], pre['sources'])


if __name__ == '__main__':
    unittest.main()
