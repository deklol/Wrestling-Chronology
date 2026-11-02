package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class SegmentWindowTest {
  @Test public void largerArchiveBlocksRetainTheShortFinalRange() throws Exception {
    byte[] video = new byte[3 * 1024 * 1024 + 13];
    new Random(17).nextBytes(video);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try (SegmentWindow reader = new SegmentWindow(Arrays.copyOf(video, SegmentWindow.BLOCK), 0, video.length,
        (start, count) -> Arrays.copyOfRange(video, (int) start, (int) start + count), pool, 1024 * 1024)) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192]; int read;
      while ((read = reader.read(chunk, 0, chunk.length)) >= 0) output.write(chunk, 0, read);
      assertArrayEquals(video, output.toByteArray());
    } finally { pool.shutdownNow(); }
  }

  @Test public void outOfOrderNetworkReadsPreserveEveryByteAndSeekBoundary() throws Exception {
    byte[] video = new byte[SegmentWindow.BLOCK * 9 + 83];
    new Random(31).nextBytes(video);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    int start = 917, end = video.length - 37;
    try (SegmentWindow reader = new SegmentWindow(Arrays.copyOfRange(video,start,start+SegmentWindow.BLOCK),start,end,
        (at,count) -> {
          try { Thread.sleep(at % 5); } catch (InterruptedException e) { throw new InterruptedIOException(); }
          return Arrays.copyOfRange(video,(int)at,(int)at+count);
        },pool)) {
      ByteArrayOutputStream result = new ByteArrayOutputStream();
      byte[] buffer = new byte[19763]; int count;
      assertEquals(0,reader.read(buffer,0,0));
      while ((count=reader.read(buffer,0,buffer.length)) != -1) result.write(buffer,0,count);
      assertArrayEquals(Arrays.copyOfRange(video,start,end),result.toByteArray());
      assertEquals(-1,reader.read(buffer,0,1));
    } finally { pool.shutdownNow(); }
  }

  @Test public void pausedReaderHasBoundedPrefetchAndCloseCancelsRequests() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch started = new CountDownLatch(4), cancelled = new CountDownLatch(4);
    AtomicInteger calls = new AtomicInteger();
    SegmentWindow reader = new SegmentWindow(new byte[1],0,Long.MAX_VALUE,(at,count) -> {
      calls.incrementAndGet();started.countDown();
      try { new CountDownLatch(1).await(); }
      catch (InterruptedException e) { cancelled.countDown();throw new InterruptedIOException(); }
      return new byte[count];
    },pool);
    try {
      assertTrue(started.await(2,TimeUnit.SECONDS));
      assertEquals(4,calls.get());
      reader.close();
      assertTrue(cancelled.await(2,TimeUnit.SECONDS));
      assertThrows(IOException.class,()->reader.read(new byte[1],0,1));
    } finally { reader.close();pool.shutdownNow(); }
  }

  @Test public void truncatedRangesFailWithoutReturningCorruptedVideo() throws Exception {
    ExecutorService pool=Executors.newSingleThreadExecutor();
    try (SegmentWindow reader=new SegmentWindow(new byte[]{12},0,100,(at,count)->new byte[count-1],pool)) {
      assertEquals(1,reader.read(new byte[1],0,1));
      assertThrows(EOFException.class,()->reader.read(new byte[100],0,100));
    } finally {pool.shutdownNow();}
  }

  @Test public void validatesExactServerOffsetsIncludingTheShortFinalRange() throws Exception {
    assertEquals(256,new SegmentRange("bytes 1024-1279/9000",1024,256).length());
    assertEquals(26,new SegmentRange("bytes 1024-1049/1050",1024,256).length());
    for (String bad:new String[]{"bytes 0-255/9000","bytes 1024-1279/*","bytes 1024-1280/9000","bytes 1024-1049/1049","bytes 99999999999999999999-999999999999999999999/4"})
      assertThrows(IOException.class,()->new SegmentRange(bad,1024,256));
  }
}
