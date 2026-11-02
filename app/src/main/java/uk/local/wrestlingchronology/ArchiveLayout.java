package uk.local.wrestlingchronology;

import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Maps a compact MP4 header to unchanged payload bytes at Internet Archive. */
final class ArchiveLayout {
  final long headerLength, total;
  final long[][] segments;

  ArchiveLayout(JSONObject data) throws Exception {
    headerLength = data.getLong("headerLength");
    total = data.getLong("total");
    long original = data.getLong("originalTotal");
    JSONArray rows = data.getJSONArray("segments");
    if (headerLength < 8 || headerLength > 64L*1024*1024 || total <= headerLength
        || original < 1 || rows.length() < 1 || rows.length() > 2)
      throw new IOException("Invalid Archive layout");
    segments = new long[rows.length()][3];
    long expected = headerLength;
    for (int i=0; i<rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      long start=row.getLong("start"), source=row.getLong("source"), size=row.getLong("length");
      if (start!=expected || size<1 || source<0 || size>original-source || size>total-start)
        throw new IOException("Inconsistent Archive layout");
      segments[i]=new long[]{start,source,size};
      expected+=size;
    }
    if (expected!=total) throw new IOException("Incomplete Archive layout");
  }

  /** Physical offset and contiguous bytes remaining at a virtual position. */
  long[] range(long position) throws IOException {
    if (position<0 || position>=total) throw new IOException("Archive position is out of bounds");
    if (position<headerLength) return new long[]{position,headerLength-position};
    for (long[] segment:segments)
      if (position>=segment[0] && position-segment[0]<segment[2])
        return new long[]{segment[1]+position-segment[0], segment[2]-(position-segment[0])};
    throw new IOException("Archive payload range is missing");
  }
}
