package uk.local.wrestlingchronology;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import java.io.EOFException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Fetches only MP4 metadata from the guide service; media streams directly. */
@androidx.media3.common.util.UnstableApi
final class ArchiveIndexDataSource implements DataSource {
  private final DataSource upstream;
  private final ArchiveLayout layout;
  private final Uri header;
  private DataSpec original;
  private long position, remaining, partRemaining;
  private boolean opened;

  ArchiveIndexDataSource(DataSource upstream, ArchiveLayout layout, String header) {
    this.upstream=upstream;
    this.layout=layout;
    this.header=Uri.parse(header);
  }

  @Override public void addTransferListener(TransferListener listener) {
    upstream.addTransferListener(listener);
  }

  @Override public long open(DataSpec spec) throws IOException {
    original=spec;
    position=spec.position;
    if (position<0 || position>layout.total) throw new IOException("Invalid Archive position");
    remaining=layout.total-position;
    if (spec.length!=C.LENGTH_UNSET) remaining=Math.min(remaining,spec.length);
    if (remaining>0) nextPart();
    return remaining;
  }

  private void nextPart() throws IOException {
    long[] part=layout.range(position);
    partRemaining=Math.min(remaining,part[1]);
    Uri uri=position<layout.headerLength ? header : original.uri;
    DataSpec spec=original.buildUpon().setUri(uri).setPosition(part[0]).setLength(partRemaining).build();
    opened=true;
    upstream.open(spec);
  }

  @Override public int read(byte[] data,int offset,int length) throws IOException {
    if (length==0) return 0;
    if (remaining==0) return C.RESULT_END_OF_INPUT;
    if (partRemaining==0) { closePart(); nextPart(); }
    int count=upstream.read(data,offset,(int)Math.min(length,partRemaining));
    if (count==C.RESULT_END_OF_INPUT) throw new EOFException("Archive range ended early");
    if (count>0) { position+=count;remaining-=count;partRemaining-=count; }
    return count;
  }

  private void closePart() throws IOException {
    if (opened) { opened=false;upstream.close(); }
  }

  @Override public Uri getUri() { return original==null ? null : original.uri; }
  @Override public Map<String,List<String>> getResponseHeaders() { return upstream.getResponseHeaders(); }
  @Override public void close() throws IOException {
    try { closePart(); } finally { original=null;remaining=0;partRemaining=0; }
  }
}
