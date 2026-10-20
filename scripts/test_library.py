import unittest
import json
from unittest.mock import patch
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from library import broadcast_date, episode_thumbnail, scan_archive, scan_youtube, add_record, programme


class LibraryTests(unittest.TestCase):
    def test_upload_scan_checks_owner_and_merges_ppv_with_existing_card(self):
        library = {'episodes': [{'id': 'wwe:1995-01-22:ppv:royalrumble', 'date': '1995-01-22',
                                'show': 'PPV', 'title': 'Royal Rumble', 'sources': [], 'thumbnail': ''}]}
        video = {'videoId': 'abcdefghijk', 'title': {'simpleText': 'FULL EVENT: WWE Royal Rumble 1995'},
                 'lengthText': {'simpleText': '2:30:00'}}
        html = 'var ytInitialData = ' + json.dumps({'playlistVideoRenderer': video}) + ';'
        config = {'youtube': {'channelId': 'UCJ5v_MCY6GNUBTO8-D3XoAg', 'videos': []}}
        playlist = {'id': 'uploads-playlist', 'show': 'Auto', 'authorUrl': 'https://www.youtube.com/@WWE'}
        with patch('library.public_request', side_effect=[html, json.dumps({'author_url': playlist['authorUrl']})]), \
             patch('library.read', return_value={'wwe': [{'date': '1995-01-22', 'name': 'Royal Rumble'}]}):
            scan_youtube(library, 'wwe', config, playlist)
        self.assertEqual(1, len(library['episodes']))
        self.assertEqual('abcdefghijk', library['episodes'][0]['sources'][0]['videoId'])
        self.assertEqual(1, len(config['youtube']['videos']))

    def test_wrong_uploader_never_becomes_an_approved_source(self):
        video = {'videoId': 'abcdefghijk', 'title': {'simpleText': 'FULL EPISODE: WWE Raw January 16 1995'},
                 'lengthText': {'simpleText': '1:00:00'}}
        html = 'var ytInitialData = ' + json.dumps({'playlistVideoRenderer': video}) + ';'
        config = {'youtube': {'channelId': 'UCJ5v_MCY6GNUBTO8-D3XoAg', 'videos': []}}
        library = {'episodes': []}
        with patch('library.public_request', side_effect=[html, json.dumps({'author_url': 'https://www.youtube.com/@SomeoneElse'})]), \
             patch('library.read', return_value={'wwe': []}):
            scan_youtube(library, 'wwe', config, {'id': 'playlist', 'show': 'Auto', 'authorUrl': 'https://www.youtube.com/@WWE'})
        self.assertEqual([], library['episodes'])
        self.assertEqual([], config['youtube']['videos'])

    def test_new_source_keeps_imported_broadcast_id(self):
        library = {'episodes': [{'id': '1997-01-06:nitro', 'date': '1997-01-06', 'show': 'Nitro',
                                'title': 'WCW Monday Nitro', 'sources': [], 'thumbnail': ''}]}
        add_record(library, 'wcw', '1997-01-06', 'Nitro', 'Nitro',
                   {'type': 'archive', 'url': 'https://archive.org/download/year/episode.mp4'})
        self.assertEqual(1, len(library['episodes']))
        self.assertEqual('1997-01-06:nitro', library['episodes'][0]['id'])

    def test_file_programme_overrides_collection_hint(self):
        self.assertEqual('Thunder', programme('WCW Thunder 2000-01-19.mp4', 'Nitro'))
        self.assertEqual('PPV', programme('ECW Heat Wave 1998.mp4', 'PPV'))
        self.assertEqual('', programme('2000-01-19.mp4'))

    def test_explicit_numeric_date_orders(self):
        self.assertEqual("2014-05-01", broadcast_date("Impact 1.5.2014.mp4", order="DMY"))
        self.assertEqual("2014-01-16", broadcast_date("Genesis.01.16.2014.avi", order="DMY"))
        self.assertEqual("2014-07-31", broadcast_date("Destination.X.7-31-14.mp4", year=2014, order="DMY"))

    def test_thumbnail_belongs_to_this_video(self):
        metadata = {"files": [
            {"name": "episode-a_000300.jpg", "original": "a.mp4", "format": "Thumbnail"},
            {"name": "episode-b_000300.jpg", "original": "b.mp4", "format": "Thumbnail"},
            {"name": "collection-cover.jpg", "format": "Item Tile"},
        ]}
        first = episode_thumbnail("year-collection", {"name": "a.mp4", "length": 900}, metadata)
        second = episode_thumbnail("year-collection", {"name": "b.mp4", "length": 900}, metadata)
        self.assertIn("episode-a", first)
        self.assertIn("episode-b", second)
        self.assertNotEqual(first, second)
        self.assertEqual("", episode_thumbnail("year-collection", {"name": "c.mp4"}, metadata))

    def test_incomplete_part_set_is_not_an_episode(self):
        metadata = {"files": [{"name": f"2010.01.04.CD{part}.avi", "source": "original", "length": 3000, "size": 10000000} for part in (1, 3)]}
        library = {"episodes": []}
        report = scan_archive(library, "tna", {"id": "collection", "showHint": "Impact"}, metadata, [])
        self.assertEqual([], library["episodes"])
        self.assertEqual(2, len(report["skipped"]))

    def test_complete_multipart_show_is_one_card(self):
        metadata = {"files": [{"name": f"2010.01.04.CD{part}.avi", "source": "original", "length": 3000, "size": 10000000} for part in (1, 2)]}
        library = {"episodes": []}
        scan_archive(library, "tna", {"id": "collection", "showHint": "Impact"}, metadata, [])
        self.assertEqual(1, len(library["episodes"]))
        source = library["episodes"][0]["sources"][0]
        self.assertEqual(2, len(source["urls"]))
        self.assertEqual(6000, source["duration"])


if __name__ == "__main__":
    unittest.main()
