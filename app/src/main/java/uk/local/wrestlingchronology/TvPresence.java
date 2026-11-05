package uk.local.wrestlingchronology;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.media3.common.Player;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** One ephemeral lease per TV screen, shared with the website's channel totals. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class TvPresence {
  interface Paint {void draw(Map<String,Integer> counts);}
  private final CommunityApi identity;
  private final Paint paint;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService io=Executors.newSingleThreadExecutor();
  private final Map<String,Integer> counts=new HashMap<>();
  private String attempt=UUID.randomUUID().toString().replace("-",""),lease,generation,sent;
  private long seq,next,retry,fullAt,countsAt;
  private long lastGuideRead;
  private final Map<String,Long> ages=new HashMap<>();
  private int failures;
  private final TvPresenceState playback=new TvPresenceState();
  private boolean busy,foreground=true,closed;

  TvPresence(android.content.Context context,Paint paint){identity=new CommunityApi(context);this.paint=paint;}
  void sample(String channel,Player player,String want){
    if(closed)return;
    long now=SystemClock.elapsedRealtime();
    long position=player==null?0:player.getCurrentPosition();
    String active=playback.sample(now,channel,player,position,foreground && player!=null && player.getPlayWhenReady(),
        player!=null && player.isPlaying(),player==null || player.getPlayerError()!=null || player.getPlaybackState()==Player.STATE_ENDED);
    boolean expired=ages.entrySet().removeIf(entry->{if(now-entry.getValue()<=90000)return false;counts.remove(entry.getKey());return true;});
    if(expired)paint.draw(new HashMap<>(counts));
    boolean needsGuide=want.equals("all") && now-fullAt>60000 && now-lastGuideRead>15000;
    if(!busy && now>=retry && (!Objects.equals(active,sent) || now>=next || needsGuide))send(active,want);
  }

  private void send(String active,String want){
    if(active==null && sent==null && want.equals("none")){next=SystemClock.elapsedRealtime()+60000;return;}
    busy=true;
    if(want.equals("all"))lastGuideRead=SystemClock.elapsedRealtime();
    io.execute(()->{
      try {
        if(closed)return;
        if(active!=null && lease==null)lease=call("open",new JSONObject().put("attempt",attempt),false).getString("lease");
        if(closed)return;
        JSONObject data=lease!=null ? call("update",new JSONObject().put("seq",++seq)
            .put("channel",active==null?JSONObject.NULL:active).put("want",want),true):call("counts",null,false);
        main.post(()->{
          if(closed)return;
          sent=active;failures=0;retry=0;next=SystemClock.elapsedRealtime()+jitter(60000);
          if(data!=null)accept(data);
        });
      }catch(Exception error){
        main.post(()->{
          if(closed)return;
          counts.clear();countsAt=0;paint.draw(Collections.emptyMap());
          failures=Math.min(5,failures+1);
          if(error instanceof PresenceError && ((PresenceError)error).status==409){lease=null;attempt=UUID.randomUUID().toString().replace("-","");retry=0;next=0;}
          else retry=SystemClock.elapsedRealtime()+jitter(Math.max(error instanceof PresenceError?((PresenceError)error).retryMs:0,Math.min(300000,10000L*(1<<failures))));
        });
      }finally{main.post(()->busy=false);}
    });
  }

  private static long jitter(long delay){return (long)(delay*(.9+Math.random()*.2));}
  private void accept(JSONObject data){
    if(data.optBoolean("disabled") || data.optBoolean("warming")){counts.clear();ages.clear();countsAt=0;fullAt=SystemClock.elapsedRealtime();paint.draw(Collections.emptyMap());return;}
    String current=data.optString("generation");
    if(!Objects.equals(generation,current)){counts.clear();ages.clear();}generation=current;
    long at=SystemClock.elapsedRealtime()-Math.max(0,data.optLong("ageSeconds"))*1000;
    JSONObject all=data.optJSONObject("counts");
    if(all!=null){counts.clear();ages.clear();for(Iterator<String> keys=all.keys();keys.hasNext();){String key=keys.next();counts.put(key,Math.max(0,all.optInt(key)));ages.put(key,at);}fullAt=at;}
    else if(data.has("channel")){String key=data.optString("channel");counts.put(key,Math.max(0,data.optInt("count")));ages.put(key,at);}
    countsAt=at;paint.draw(new HashMap<>(counts));
  }

  void foreground(boolean value){foreground=value;playback.reset();if(!value)leave();else next=0;}
  private void leave(){
    io.execute(()->{try{if(lease!=null)call("update",new JSONObject().put("seq",++seq)
        .put("channel",JSONObject.NULL).put("want","none"),true);}catch(Exception ignored){}
        main.post(()->sent=null);});
  }
  void close(){if(closed)return;closed=true;foreground=false;leave();io.shutdown();counts.clear();}

  private JSONObject call(String action,JSONObject body,boolean presence)throws Exception {
    HttpURLConnection connection=(HttpURLConnection)new URL(BuildConfig.COMMUNITY_BASE_URL+"/tv/presence/v1/"+action).openConnection();
    connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setInstanceFollowRedirects(false);
    connection.setRequestProperty("Authorization",presence?"Presence "+lease:"Bearer "+identity.tvCredential());
    connection.setRequestProperty("Accept","application/json");
    try {
      if(body!=null){byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);connection.setRequestMethod("POST");
        connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(data.length);
        try(OutputStream out=connection.getOutputStream()){out.write(data);}}
      int status=connection.getResponseCode();
      if(status<200 || status>=300){long delay=0;try{delay=Long.parseLong(connection.getHeaderField("Retry-After"))*1000;}catch(Exception ignored){}
        throw new PresenceError(status,Math.min(300000,delay));}
      if(status==204)return null;
      try(InputStream in=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
        byte[] buffer=new byte[2048];int count;
        while((count=in.read(buffer))!=-1){if(bytes.size()+count>32768)throw new IOException("Presence response too large");bytes.write(buffer,0,count);}
        return new JSONObject(bytes.toString("UTF-8"));}
    }finally{connection.disconnect();}
  }
  private static final class PresenceError extends IOException {
    final int status;final long retryMs;
    PresenceError(int status,long retryMs){this.status=status;this.retryMs=retryMs;}
  }
}
