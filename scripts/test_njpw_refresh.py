import unittest
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from njpw_sources import known_archive_items


class KnownArchiveItemsTest(unittest.TestCase):
    def test_existing_single_and_multipart_recordings_seed_discovery(self):
        rows = [{'sources': [
            {'type': 'archive', 'url': 'https://archive.org/download/already%20known/show.mp4'},
            {'type': 'archive', 'urls': ['https://archive.org/download/multipart/disc1.mp4',
                                       'https://archive.org/download/multipart/disc2.mp4']},
            {'type': 'youtube', 'videoId': 'abcdefghijk'},
            {'type': 'archive', 'url': 'https://other.example/download/unrelated/show.mp4'}]}]
        self.assertEqual({'already known', 'multipart'}, known_archive_items(rows))


if __name__ == '__main__':
    unittest.main()
