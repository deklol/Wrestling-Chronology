"""Keep main cards, pre-shows and separately broadcast specials distinct."""
import re


def event_part(title):
    if re.search(r'(?i)with[ ._-]*(?:zero[ ._-]*hour|buy[ ._-]*in|pre[ ._-]*show)', title):
        return 'main'
    if re.search(r'(?i)tailgate[ ._-]*brawl', title):
        return 'tailgate'
    if re.search(r'(?i)\b(?:zero[ ._-]*hour|buy[ ._-]*in|pre[ ._-]*show|kick[ ._-]*off)\b', title):
        return 'preshow'
    return 'main'


def row_part(row):
    return row.get('eventPart') or event_part(row.get('title', ''))


def event_title(name, part, original):
    if part == 'main':
        return name
    if part == 'tailgate':
        label = 'Saturday Tailgate Brawl'
    elif re.search(r'(?i)zero[ ._-]*hour', original):
        label = 'Zero Hour'
    elif re.search(r'(?i)buy[ ._-]*in', original):
        label = 'Buy In'
    else:
        label = 'Pre-show'
    return name + ' — ' + label


def apply_source_corrections(library, config):
    """Only remove an old association when the reviewed destination has the source."""
    rows = library['episodes']
    by_id = {row['id']: row for row in rows}
    from library import source_key
    assignments = library.get('sourceAssignments', {})
    confirmed = {key: destination for key, destination in assignments.items()
                 if destination in by_id and any(source_key(source) == key
                                                for source in by_id[destination]['sources'])}
    retired = set(config.get('retiredSources', []))
    for row in rows:
        row['sources'] = [source for source in row['sources']
                          if source_key(source) not in retired
                          and confirmed.get(source_key(source), row['id']) == row['id']]
    library['episodes'] = [row for row in rows if row['sources']]
