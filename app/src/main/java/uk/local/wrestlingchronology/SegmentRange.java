package uk.local.wrestlingchronology;

import java.io.IOException;
import java.util.regex.*;

/** Validate the actual range, including hosts returning HTTP 200 with Content-Range. */
final class SegmentRange {
  private static final Pattern HEADER = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
  final long start, end, total;

  SegmentRange(String header, long requestedStart, int requestedLength) throws IOException {
    Matcher match = HEADER.matcher(header == null ? "" : header);
    if (!match.matches()) throw new IOException("Missing video byte range");
    try {
      start = Long.parseLong(match.group(1));
      end = Long.parseLong(match.group(2));
      total = Long.parseLong(match.group(3));
    } catch (NumberFormatException error) { throw new IOException("Invalid video byte range", error); }
    if (requestedStart < 0 || requestedLength <= 0 || start != requestedStart || total <= start
        || end < start || end != start + Math.min(requestedLength, total - start) - 1)
      throw new IOException("Video host returned a different byte range");
  }

  int length() { return (int) (end - start + 1); }
}
