"""Run locally or in GitHub Actions; never uploads videos or rebuilds the APK."""
import argparse
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from library import publish_packs

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="https://raw.githubusercontent.com/deklol/Wrestling-Chronology/main/catalogue")
    parser.add_argument("--refresh", action="store_true")
    options = parser.parse_args()
    result = publish_packs(options.base_url, refresh=options.refresh)
    for company, entry in result["companies"].items():
        print(company, entry["revision"], entry["bytes"], entry["sha256"])
