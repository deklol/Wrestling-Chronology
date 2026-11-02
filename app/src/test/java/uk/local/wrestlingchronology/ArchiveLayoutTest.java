package uk.local.wrestlingchronology;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class ArchiveLayoutTest {
  private JSONObject data() throws Exception {
    return new JSONObject("{headerLength:100,total:900,originalTotal:1000,segments:["
        + "{start:100,source:20,length:300},{start:400,source:500,length:500}]}");
  }

  @Test public void mapsHeaderAndPayloadAcrossBothBoundaries() throws Exception {
    ArchiveLayout layout=new ArchiveLayout(data());
    assertArrayEquals(new long[]{99,1},layout.range(99));
    assertArrayEquals(new long[]{20,300},layout.range(100));
    assertArrayEquals(new long[]{319,1},layout.range(399));
    assertArrayEquals(new long[]{500,500},layout.range(400));
    assertArrayEquals(new long[]{999,1},layout.range(899));
  }
  @Test public void rejectsGapsOverlapsAndChangedFileSizes() throws Exception {
    for (long start:new long[]{399,401}) {
      JSONObject broken=data();broken.getJSONArray("segments").getJSONObject(1).put("start",start);
      assertThrows(Exception.class,()->new ArchiveLayout(broken));
    }
    JSONObject changed=data().put("originalTotal",999);
    assertThrows(Exception.class,()->new ArchiveLayout(changed));
  }
  @Test public void rejectsUnboundedHeaderAndOutOfRangeSeeks() throws Exception {
    assertThrows(Exception.class,()->new ArchiveLayout(data().put("headerLength",Integer.MAX_VALUE)));
    ArchiveLayout layout=new ArchiveLayout(data());
    assertThrows(Exception.class,()->layout.range(-1));
    assertThrows(Exception.class,()->layout.range(900));
  }
}
