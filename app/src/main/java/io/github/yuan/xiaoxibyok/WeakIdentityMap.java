package io.github.yuan.xiaoxibyok;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

final class WeakIdentityMap {
  static final ReferenceQueue<Object> queue = new ReferenceQueue<>();
  static final Map<Key, Map<String, Object>> values = new HashMap<>();

  static final class Key extends WeakReference<Object> {
    final int hash;

    Key(Object owner, ReferenceQueue<Object> queue) {
      super(owner, queue);
      hash = System.identityHashCode(owner);
    }

    public int hashCode() {
      return hash;
    }

    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Key)) return false;
      Object owner = get();
      return owner != null && owner == ((Key) other).get();
    }
  }

  static synchronized Object get(Object owner, String name) {
    expunge();
    Map<String, Object> fields = values.get(new Key(owner, queue));
    return fields == null ? null : fields.get(name);
  }

  static synchronized void put(Object owner, String name, Object value) {
    expunge();
    Key key = new Key(owner, queue);
    if (value == null) {
      Map<String, Object> fields = values.get(key);
      if (fields != null) {
        fields.remove(name);
        if (fields.isEmpty()) values.remove(key);
      }
      return;
    }
    values.computeIfAbsent(key, ignored -> new HashMap<>()).put(name, value);
  }

  static void expunge() {
    Key key;
    while ((key = (Key) queue.poll()) != null) values.remove(key);
  }
}
