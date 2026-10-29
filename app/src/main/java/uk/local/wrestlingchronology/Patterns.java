package uk.local.wrestlingchronology;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
/** Share common filename expressions without retaining arbitrary input forever. */
final class Patterns {
  private static final ConcurrentHashMap<String,Pattern> cache = new ConcurrentHashMap<>();
  static Pattern get(String expression) { return get(expression, 0); }
  static Pattern get(String expression, int flags) {
    String key = flags + ":" + expression;
    Pattern result = cache.get(key);
    if (result != null) return result;
    result = Pattern.compile(expression, flags);
    if (cache.size() < 96) {
      Pattern previous = cache.putIfAbsent(key, result);
      if (previous != null) return previous;
    }
    return result;
  }
}
