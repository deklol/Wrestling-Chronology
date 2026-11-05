package uk.local.wrestlingchronology;

/** A pending decoder and a focused guide row are never viewers. */
final class TvPresenceState {
  private Object previous;
  private long position,lastProgress=-1;
  String sample(long now,String channel,Object player,long current,boolean intent,boolean playing,boolean terminal){
    boolean same=player==previous;
    boolean playable=channel!=null && player!=null && intent && !terminal;
    if(playable && same && playing && current>position && current-position<5000)lastProgress=now;
    if(!same || !playable)lastProgress=-1;
    previous=player;position=current;
    return playable && lastProgress>=0 && now-lastProgress<10000?channel:null;
  }
  void reset(){lastProgress=-1;previous=null;}
}
