package io.github.yuan.xiaoxibyok.host;

public final class HostContinuation extends kotlin.coroutines.jvm.internal.ContinuationImpl {
  public HostContinuation(kotlin.coroutines.Continuation<Object> c) {
    super(c);
  }

  @Override
  protected Object invokeSuspend(Object result) {
    return result;
  }
}
