/*
 * Copyright 2023 The Fury Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fury.util.unsafe;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Primitives;
import io.fury.collection.Tuple2;
import io.fury.util.Utils;
import io.fury.util.function.ToByteFunction;
import io.fury.util.function.ToCharFunction;
import io.fury.util.function.ToFloatFunction;
import io.fury.util.function.ToShortFunction;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import sun.misc.Unsafe;

// CHECKSTYLE.OFF:TypeName
public class _JDKAccess {
  // CHECKSTYLE.ON:TypeName
  public static final int JAVA_VERSION;
  public static final boolean OPEN_J9;
  public static final Unsafe UNSAFE;
  public static final Class<?> _INNER_UNSAFE_CLASS;
  public static final Object _INNER_UNSAFE;

  static {
    String property = System.getProperty("java.specification.version");
    if (property.startsWith("1.")) {
      property = property.substring(2);
    }
    String jmvName = System.getProperty("java.vm.name", "");
    OPEN_J9 = jmvName.contains("OpenJ9");
    JAVA_VERSION = Integer.parseInt(property);

    Unsafe unsafe;
    try {
      Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      unsafe = (Unsafe) unsafeField.get(null);
    } catch (Throwable cause) {
      throw new UnsupportedOperationException("Unsafe is not supported in this platform.");
    }
    UNSAFE = unsafe;
    if (JAVA_VERSION >= 11) {
      try {
        Field theInternalUnsafeField = Unsafe.class.getDeclaredField("theInternalUnsafe");
        theInternalUnsafeField.setAccessible(true);
        _INNER_UNSAFE = theInternalUnsafeField.get(null);
        _INNER_UNSAFE_CLASS = _INNER_UNSAFE.getClass();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    } else {
      _INNER_UNSAFE_CLASS = null;
      _INNER_UNSAFE = null;
    }
  }

  private static final ClassValue<Lookup> lookupCache =
      new ClassValue<Lookup>() {
        @Override
        protected Lookup computeValue(Class type) {
          return _Lookup._trustedLookup(type);
        }
      };

  // CHECKSTYLE.OFF:MethodName

  public static Lookup _trustedLookup(Class<?> objectClass) {
    // CHECKSTYLE.ON:MethodName
    return lookupCache.get(objectClass);
  }

  public static <T> T tryMakeFunction(
      Lookup lookup, MethodHandle handle, Class<T> functionInterface) {
    try {
      return makeFunction(lookup, handle, functionInterface);
    } catch (Throwable e) {
      Utils.ignore(e);
      throw new IllegalStateException();
    }
  }

  private static final MethodType jdkFunctionMethodType =
      MethodType.methodType(Object.class, Object.class);

  @SuppressWarnings("unchecked")
  public static <T, R> Function<T, R> makeJDKFunction(Lookup lookup, MethodHandle handle) {
    try {
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              "apply",
              MethodType.methodType(Function.class),
              jdkFunctionMethodType,
              handle,
              boxedMethodType(handle.type()));
      return (Function<T, R>) callSite.getTarget().invokeExact();
    } catch (Throwable e) {
      UNSAFE.throwException(e);
      throw new IllegalStateException(e);
    }
  }

  private static final MethodType jdkConsumerMethodType =
      MethodType.methodType(void.class, Object.class);

  @SuppressWarnings("unchecked")
  public static <T> Consumer<T> makeJDKConsumer(Lookup lookup, MethodHandle handle) {
    try {
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              "accept",
              MethodType.methodType(Consumer.class),
              jdkConsumerMethodType,
              handle,
              boxedMethodType(handle.type()));
      return (Consumer<T>) callSite.getTarget().invokeExact();
    } catch (Throwable e) {
      UNSAFE.throwException(e);
      throw new IllegalStateException(e);
    }
  }

  private static final MethodType jdkBiConsumerMethodType =
      MethodType.methodType(void.class, Object.class, Object.class);

  @SuppressWarnings("unchecked")
  public static <T, U> BiConsumer<T, U> makeJDKBiConsumer(Lookup lookup, MethodHandle handle) {
    try {
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              "accept",
              MethodType.methodType(BiConsumer.class),
              jdkBiConsumerMethodType,
              handle,
              boxedMethodType(handle.type()));
      return (BiConsumer<T, U>) callSite.getTarget().invokeExact();
    } catch (Throwable e) {
      UNSAFE.throwException(e);
      throw new IllegalStateException(e);
    }
  }

  private static MethodType boxedMethodType(MethodType methodType) {
    Class<?>[] paramTypes = new Class[methodType.parameterCount()];
    for (int i = 0; i < paramTypes.length; i++) {
      Class<?> t = methodType.parameterType(i);
      if (t.isPrimitive()) {
        t = Primitives.wrap(t);
      }
      paramTypes[i] = t;
    }
    return MethodType.methodType(methodType.returnType(), paramTypes);
  }

  @SuppressWarnings("unchecked")
  public static <T> T makeFunction(Lookup lookup, MethodHandle handle, Method methodToImpl) {
    MethodType instantiatedMethodType = boxedMethodType(handle.type());
    MethodType methodToImplType =
        MethodType.methodType(methodToImpl.getReturnType(), methodToImpl.getParameterTypes());
    try {
      // Faster than handle.invokeExact.
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              methodToImpl.getName(),
              MethodType.methodType(methodToImpl.getDeclaringClass()),
              methodToImplType,
              handle,
              instantiatedMethodType);
      return (T) callSite.getTarget().invokeExact();
    } catch (Throwable e) {
      UNSAFE.throwException(e);
      throw new IllegalStateException(e);
    }
  }

  public static <T> T makeFunction(Lookup lookup, MethodHandle handle, Class<T> functionInterface) {
    String invokedName = "apply";
    try {
      Method method = null;
      Method[] methods = functionInterface.getMethods();
      for (Method interfaceMethod : methods) {
        if (interfaceMethod.getName().equals(invokedName)) {
          method = interfaceMethod;
          break;
        }
      }
      if (method == null) {
        Preconditions.checkArgument(methods.length == 1);
        method = methods[0];
        invokedName = method.getName();
      }
      MethodType interfaceType =
          MethodType.methodType(method.getReturnType(), method.getParameterTypes());
      // Faster than handle.invokeExact.
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              invokedName,
              MethodType.methodType(functionInterface),
              interfaceType,
              handle,
              interfaceType);
      // FIXME(chaokunyang) why use invokeExact will fail.
      return (T) callSite.getTarget().invoke();
    } catch (Throwable e) {
      UNSAFE.throwException(e);
      throw new IllegalStateException(e);
    }
  }

  private static final Map<Class<?>, Tuple2<Class<?>, String>> methodMap = new HashMap<>();

  static {
    methodMap.put(boolean.class, Tuple2.of(Predicate.class, "test"));
    methodMap.put(byte.class, Tuple2.of(ToByteFunction.class, "applyAsByte"));
    methodMap.put(char.class, Tuple2.of(ToCharFunction.class, "applyAsChar"));
    methodMap.put(short.class, Tuple2.of(ToShortFunction.class, "applyAsShort"));
    methodMap.put(int.class, Tuple2.of(ToIntFunction.class, "applyAsInt"));
    methodMap.put(long.class, Tuple2.of(ToLongFunction.class, "applyAsLong"));
    methodMap.put(float.class, Tuple2.of(ToFloatFunction.class, "applyAsFloat"));
    methodMap.put(double.class, Tuple2.of(ToDoubleFunction.class, "applyAsDouble"));
  }

  public static Object makeGetterFunction(
      MethodHandles.Lookup lookup, MethodHandle handle, Class<?> returnType) {
    Tuple2<Class<?>, String> methodInfo = methodMap.get(returnType);
    MethodType factoryType;
    if (methodInfo == null) {
      methodInfo = Tuple2.of(Function.class, "apply");
      factoryType = jdkFunctionMethodType;
    } else {
      factoryType = MethodType.methodType(returnType, Object.class);
    }
    try {
      CallSite callSite =
          LambdaMetafactory.metafactory(
              lookup,
              methodInfo.f1,
              MethodType.methodType(methodInfo.f0),
              factoryType,
              handle,
              handle.type());
      // Can't use invokeExact, since we can't specify exact target type for return variable.
      return callSite.getTarget().invoke();
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      // ToByteFunction/ToBoolFunction/.. are not defined in jdk, if the classloader of
      // fury functions `ToByteFunction/..` isn't parent classloader of classloader for getter
      // represented by handle, then exception will be thrown.
      return makeGetterFunction(lookup, handle, Object.class);
    } catch (Throwable e) {
      UNSAFE.throwException(e);
      throw new IllegalStateException(e);
    }
  }
}
