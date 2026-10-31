package uk.local.wrestlingchronology;

import androidx.media3.common.*;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.function.IntSupplier;

/** Change upcoming HLS chunks without replacing the active track selection or its buffer. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class BufferedQualitySelection extends AdaptiveTrackSelection.Factory {
  private final IntSupplier requested, ceiling;

  BufferedQualitySelection(IntSupplier requested, IntSupplier ceiling) {
    this.requested = requested;
    this.ceiling = ceiling;
  }

  @Override protected AdaptiveTrackSelection createAdaptiveTrackSelection(
      TrackGroup group, int[] tracks, int type, BandwidthMeter meter,
      ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint> checkpoints) {
    if (!MimeTypes.isVideo(group.getFormat(0).sampleMimeType))
      return super.createAdaptiveTrackSelection(group, tracks, type, meter, checkpoints);
    return new AdaptiveTrackSelection(group, tracks, meter) {
      private int manualIndex() {
        int height = requested.getAsInt();
        if (height > 0) for (int i = 0; i < length; i++)
          if (getFormat(i).height == height && !isTrackExcluded(i, android.os.SystemClock.elapsedRealtime())) return i;
        return C.INDEX_UNSET;
      }

      @Override public int getSelectedIndex() {
        int manual = manualIndex();
        if (manual != C.INDEX_UNSET) return manual;
        int selected = super.getSelectedIndex();
        // A recovery ceiling must take effect even if the previous video queue was healthy.
        if (getFormat(selected).height > ceiling.getAsInt()) {
          for (int i = 0; i < length; i++)
            if (getFormat(i).height <= ceiling.getAsInt() && !isTrackExcluded(i, android.os.SystemClock.elapsedRealtime())) return i;
        }
        return selected;
      }

      @Override public int getSelectionReason() {
        return manualIndex() == C.INDEX_UNSET ? super.getSelectionReason() : C.SELECTION_REASON_MANUAL;
      }

      @Override protected boolean canSelectFormat(Format format, int bitrate, long bandwidth) {
        int height = requested.getAsInt();
        if (height > 0) return format.height == height;
        return format.height <= ceiling.getAsInt() && super.canSelectFormat(format, bitrate, bandwidth);
      }

      @Override public int evaluateQueueSize(long positionUs, List<? extends MediaChunk> queue) {
        int manual = manualIndex();
        if (manual == C.INDEX_UNSET) return super.evaluateQueueSize(positionUs, queue);
        Format target = getFormat(manual);
        // Keep the playing chunk and enough time to fetch its replacement before trimming later chunks.
        for (int i = 1; i < queue.size(); i++) {
          MediaChunk chunk = queue.get(i);
          long reserve = reserveUs(chunk.endTimeUs - chunk.startTimeUs, target.bitrate, meter.getBitrateEstimate());
          if (chunk.trackFormat.height != target.height && chunk.startTimeUs - positionUs >= reserve) return i;
        }
        return queue.size();
      }

      @Override public boolean shouldCancelChunkLoad(long positionUs, Chunk loading, List<? extends MediaChunk> queue) {
        int manual = manualIndex();
        if (manual != C.INDEX_UNSET && loading.trackFormat != null && !queue.isEmpty()) {
          MediaChunk last = queue.get(queue.size() - 1);
          Format target = getFormat(manual);
          return loading.trackFormat.height != target.height && last.endTimeUs - positionUs >=
              reserveUs(last.endTimeUs - last.startTimeUs, target.bitrate, meter.getBitrateEstimate());
        }
        return requested.getAsInt() == 0 && loading.trackFormat != null
            && loading.trackFormat.height > ceiling.getAsInt();
      }
    };
  }

  static long reserveUs(long chunkDurationUs, int targetBitrate, long bandwidth) {
    if (chunkDurationUs <= 0 || targetBitrate <= 0 || bandwidth <= 0) return 6000000L;
    return Math.max(3000000L, (long) (chunkDurationUs * (double) targetBitrate / bandwidth * 1.5) + 1000000L);
  }
}
