package io.github.yuan.xiaoxibyok;

import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.Map;

public abstract class XC_MethodHook {
  protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

  protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

  public static final class MethodHookParam {
    public Object thisObject;
    public Object[] args;
    public Member method;
    Object result;
    Throwable throwable;
    boolean early;
    final Map<String, Object> extras = new HashMap<>();

    public void setResult(Object value) {
      result = value;
      early = true;
    }

    public Object getResult() {
      return result;
    }

    public void setThrowable(Throwable value) {
      throwable = value;
      early = true;
    }

    public Throwable getThrowable() {
      return throwable;
    }

    public boolean hasThrowable() {
      return throwable != null;
    }

    public void setObjectExtra(String key, Object value) {
      extras.put(key, value);
    }

    public Object getObjectExtra(String key) {
      return extras.get(key);
    }
  }
}
