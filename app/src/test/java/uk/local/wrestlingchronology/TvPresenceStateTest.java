package uk.local.wrestlingchronology;
import org.junit.Test;
import static org.junit.Assert.*;
public class TvPresenceStateTest {
  @Test public void initialBufferAndPreviewDoNotCount(){
    TvPresenceState state=new TvPresenceState();Object player=new Object();
    assertNull(state.sample(0,"ecw",player,0,true,false,false));
    assertNull(state.sample(1000,"ecw",player,0,true,false,false));
    assertEquals("ecw",state.sample(2000,"ecw",player,1000,true,true,false));
  }
  @Test public void onePlayerMovesBetweenChannelsWithoutCreatingAnotherViewer(){
    TvPresenceState state=new TvPresenceState();Object player=new Object();
    state.sample(0,"ecw",player,0,true,true,false);
    assertEquals("ecw",state.sample(1000,"ecw",player,1000,true,true,false));
    assertEquals("wwe",state.sample(2000,"wwe",player,2000,true,true,false));
  }
  @Test public void shortBufferGetsGraceButPauseAndErrorStopImmediately(){
    TvPresenceState state=new TvPresenceState();Object player=new Object();
    state.sample(0,"ecw",player,0,true,true,false);state.sample(1000,"ecw",player,1000,true,true,false);
    assertEquals("ecw",state.sample(9000,"ecw",player,1000,true,false,false));
    assertNull(state.sample(11000,"ecw",player,1000,true,false,false));
    state.sample(12000,"ecw",player,2000,true,true,false);
    assertNull(state.sample(13000,"ecw",player,2000,false,false,false));
    state.sample(14000,"ecw",player,3000,true,true,false);
    assertNull(state.sample(15000,"ecw",player,3000,true,false,true));
  }
}
