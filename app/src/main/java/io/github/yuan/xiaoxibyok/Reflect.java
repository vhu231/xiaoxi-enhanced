package io.github.yuan.xiaoxibyok;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

final class Reflect {
  static final Map<Class<?>, Class<?>> PRIMITIVES = new HashMap<>();

  static {
    PRIMITIVES.put(boolean.class, Boolean.class);
    PRIMITIVES.put(byte.class, Byte.class);
    PRIMITIVES.put(short.class, Short.class);
    PRIMITIVES.put(char.class, Character.class);
    PRIMITIVES.put(int.class, Integer.class);
    PRIMITIVES.put(long.class, Long.class);
    PRIMITIVES.put(float.class, Float.class);
    PRIMITIVES.put(double.class, Double.class);
  }

  static Class<?> findClass(String name, ClassLoader loader) {
    try {
      return Class.forName(name, false, loader);
    } catch (ClassNotFoundException error) {
      throw new RuntimeException(error);
    }
  }

  static Field field(Object target, String name) {
    Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      try {
        Field field = current.getDeclaredField(name);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {
      }
    }
    throw new NoSuchFieldError(type.getName() + "." + name);
  }

  static Object getObjectField(Object target, String name) {
    try {
      return field(target, name).get(target);
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static void setObjectField(Object target, String name, Object value) {
    try {
      field(target, name).set(target, value);
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static Object getStaticObjectField(Class<?> type, String name) {
    return getObjectField(type, name);
  }

  static void setStaticBooleanField(Class<?> type, String name, boolean value) {
    try {
      field(type, name).setBoolean(null, value);
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static int getIntField(Object target, String name) {
    try {
      return ((Number) field(target, name).get(target)).intValue();
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static long getLongField(Object target, String name) {
    try {
      return ((Number) field(target, name).get(target)).longValue();
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static float getFloatField(Object target, String name) {
    try {
      return ((Number) field(target, name).get(target)).floatValue();
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static float getStaticFloatField(Class<?> type, String name) {
    return getFloatField(type, name);
  }

  static boolean getBooleanField(Object target, String name) {
    try {
      return field(target, name).getBoolean(target);
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static void setIntField(Object target, String name, int value) {
    try {
      field(target, name).setInt(target, value);
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static void setFloatField(Object target, String name, float value) {
    try {
      field(target, name).setFloat(target, value);
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static void setBooleanField(Object target, String name, boolean value) {
    try {
      field(target, name).setBoolean(target, value);
    } catch (IllegalAccessException error) {
      throw new RuntimeException(error);
    }
  }

  static Object callMethod(Object target, String name, Object... args) {
    return invoke(findMethod(target.getClass(), name, args, false), target, args);
  }

  static Object callStaticMethod(Class<?> type, String name, Object... args) {
    return invoke(findMethod(type, name, args, true), null, args);
  }

  static Object invoke(Method method, Object target, Object... args) {
    try {
      return Xposed.invoke(method, target, args);
    } catch (InvocationTargetException error) {
      Xposed.<RuntimeException>throwUnchecked(error.getTargetException());
      throw new AssertionError();
    } catch (Throwable error) {
      Xposed.<RuntimeException>throwUnchecked(error);
      throw new AssertionError();
    }
  }

  static <T> T newInstance(Class<T> type, Object... args) {
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (compatible(constructor.getParameterTypes(), args) >= 0) {
        try {
          return (T) Xposed.newInstance(constructor, args);
        } catch (InvocationTargetException error) {
          Xposed.<RuntimeException>throwUnchecked(error.getTargetException());
          throw new AssertionError();
        } catch (Throwable error) {
          Xposed.<RuntimeException>throwUnchecked(error);
          throw new AssertionError();
        }
      }
    }
    throw new NoSuchMethodError(type.getName() + ".<init>");
  }

  static Method findMethod(Class<?> type, String name, Object[] args, boolean staticOnly) {
    Method best = null;
    int bestScore = Integer.MAX_VALUE;
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (!method.getName().equals(name)
            || (staticOnly && !Modifier.isStatic(method.getModifiers()))) continue;
        int score = compatible(method.getParameterTypes(), args);
        if (score >= 0 && score < bestScore) {
          best = method;
          bestScore = score;
        }
      }
    }
    if (best == null) throw new NoSuchMethodError(type.getName() + "." + name);
    return best;
  }

  static int compatible(Class<?>[] parameters, Object[] args) {
    if (parameters.length != args.length) return -1;
    int score = 0;
    for (int i = 0; i < parameters.length; i++) {
      if (args[i] == null) {
        if (parameters[i].isPrimitive()) return -1;
        score += 4;
      } else if (parameters[i].isInstance(args[i])) {
        // Exact reference matches are preferred.
      } else if (parameters[i].isPrimitive() && PRIMITIVES.get(parameters[i]).isInstance(args[i])) {
        score += 1;
      } else if (parameters[i].isPrimitive() && widening(parameters[i], args[i])) {
        score += 2;
      } else if (!parameters[i].isPrimitive()
          && parameters[i].isAssignableFrom(args[i].getClass())) {
        score += 3;
      } else {
        return -1;
      }
    }
    return score;
  }

  static boolean widening(Class<?> target, Object value) {
    if (value instanceof Byte) return target != boolean.class && target != char.class;
    if (value instanceof Short || value instanceof Character)
      return target == int.class
          || target == long.class
          || target == float.class
          || target == double.class;
    if (value instanceof Integer)
      return target == long.class || target == float.class || target == double.class;
    if (value instanceof Long) return target == float.class || target == double.class;
    if (value instanceof Float) return target == double.class;
    return false;
  }

  static Object getAdditionalInstanceField(Object target, String name) {
    return WeakIdentityMap.get(target, name);
  }

  static void setAdditionalInstanceField(Object target, String name, Object value) {
    WeakIdentityMap.put(target, name, value);
  }
}
