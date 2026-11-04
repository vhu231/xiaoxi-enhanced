package kotlin.coroutines.jvm.internal;

public abstract class ContinuationImpl {
  public ContinuationImpl(kotlin.coroutines.Continuation<Object> c) {}

  protected abstract Object invokeSuspend(Object result);
}
