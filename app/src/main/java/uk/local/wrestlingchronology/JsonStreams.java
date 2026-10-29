package uk.local.wrestlingchronology;
import android.util.JsonReader;
import android.util.JsonWriter;
import android.util.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.*;

/** Parse/write a library without retaining a full UTF-16 copy of its JSON text. */
final class JsonStreams {
  static JSONObject read(InputStream input) throws Exception {
    try (JsonReader reader = new JsonReader(new InputStreamReader(new FilterInputStream(input) {
      long count;
      void counted(int n) throws IOException {
        if (n > 0 && (count += n) > LibraryPack.MAX_BYTES) throw new IOException("Library too large");
      }
      @Override public int read() throws IOException { int n=in.read(); counted(n < 0 ? 0 : 1); return n; }
      @Override public int read(byte[] b,int o,int n) throws IOException { int got=in.read(b,o,n); counted(got); return got; }
    }, StandardCharsets.UTF_8))) {
      Object result = value(reader, 0);
      if (!(result instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT)
        throw new IOException("Invalid library JSON");
      return (JSONObject) result;
    }
  }
  private static Object value(JsonReader r, int depth) throws Exception {
    if (depth > 64) throw new IOException("JSON nesting too deep");
    switch(r.peek()) {
      case BEGIN_OBJECT:
        JSONObject o=new JSONObject();r.beginObject();
        while(r.hasNext()) {
          String name = r.nextName();
          // Import audit and filename override maps are used by the Python feed
          // generator, not by playback. Keep them in the public files, not RAM.
          if (name.equals("overrides") || name.equals("dateEvidence") || name.equals("md5")) r.skipValue();
          else o.put(name,value(r,depth+1));
        }
        r.endObject();return o;
      case BEGIN_ARRAY:
        JSONArray a=new JSONArray();r.beginArray();
        while(r.hasNext()) a.put(value(r,depth+1));
        r.endArray();return a;
      case STRING: return r.nextString();
      case NUMBER:
        String n=r.nextString();
        try {return Long.valueOf(n);} catch(NumberFormatException e){return Double.valueOf(n);}
      case BOOLEAN: return r.nextBoolean();
      case NULL: r.nextNull();return JSONObject.NULL;
      default: throw new IOException("Invalid JSON value");
    }
  }
  static void write(OutputStream output, JSONObject json) throws Exception {
    JsonWriter w=new JsonWriter(new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8), 65536));
    writeValue(w,json);w.flush(); // AtomicFile owns and closes the stream.
  }
  private static void writeValue(JsonWriter w,Object v) throws Exception {
    if(v==null || v==JSONObject.NULL){w.nullValue();return;}
    if(v instanceof JSONObject){
      JSONObject o=(JSONObject)v;w.beginObject();
      for(Iterator<String> keys=o.keys();keys.hasNext();){String k=keys.next();w.name(k);writeValue(w,o.get(k));}
      w.endObject();
    } else if(v instanceof JSONArray){
      JSONArray a=(JSONArray)v;w.beginArray();for(int i=0;i<a.length();i++)writeValue(w,a.get(i));w.endArray();
    } else if(v instanceof Boolean)w.value((Boolean)v);
    else if(v instanceof Number)w.value((Number)v);
    else w.value(String.valueOf(v));
  }
}
