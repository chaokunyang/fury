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

package io.fury.config;

import io.fury.Fury;
import io.fury.ThreadLocalFury;
import io.fury.ThreadSafeFury;
import io.fury.pool.ThreadPoolFury;
import io.fury.resolver.ClassResolver;
import io.fury.serializer.JavaSerializer;
import io.fury.serializer.ObjectStreamSerializer;
import io.fury.serializer.Serializer;
import io.fury.serializer.TimeSerializers;
import io.fury.util.LoggerFactory;
import io.fury.util.Platform;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Builder class to config and create {@link Fury}.
 *
 * @author chaokunyang
 */
@SuppressWarnings("rawtypes")
public final class FuryBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(FuryBuilder.class);

  private static final boolean ENABLE_CLASS_REGISTRATION_FORCIBLY;

  static {
    String flagValue =
        System.getProperty(
            "fury.enable_fury_security_mode_forcibly",
            System.getenv("ENABLE_CLASS_REGISTRATION_FORCIBLY"));
    if (flagValue == null) {
      flagValue = "false";
    }
    ENABLE_CLASS_REGISTRATION_FORCIBLY = "true".equals(flagValue) || "1".equals(flagValue);
  }

  boolean checkClassVersion = false;
  Language language = Language.JAVA;
  boolean trackingRef = false;
  boolean basicTypesRefIgnored = true;
  boolean stringRefIgnored = true;
  boolean timeRefIgnored = true;
  ClassLoader classLoader;
  boolean compressInt = true;
  public LongEncoding longEncoding;
  boolean compressString = true;
  CompatibleMode compatibleMode = CompatibleMode.SCHEMA_CONSISTENT;
  boolean checkJdkClassSerializable = true;
  Class<? extends Serializer> defaultJDKStreamSerializerType = ObjectStreamSerializer.class;
  boolean requireClassRegistration = true;
  boolean shareMetaContext = false;
  boolean codeGenEnabled = true;
  public boolean deserializeUnexistedClass = false;
  public boolean asyncCompilationEnabled = false;
  public boolean registerGuavaTypes = true;

  public FuryBuilder() {}

  /**
   * Whether cross-language serialize the object. If you used fury for java only, please set
   * language to {@link Language#JAVA}, which will have much better performance.
   */
  public FuryBuilder withLanguage(Language language) {
    this.language = language;
    return this;
  }

  /** Whether track shared or circular references. */
  public FuryBuilder withRefTracking(boolean trackingRef) {
    this.trackingRef = trackingRef;
    return this;
  }

  /** Whether ignore basic types shared reference. */
  public FuryBuilder ignoreBasicTypesRef(boolean ignoreBasicTypesRef) {
    this.basicTypesRefIgnored = ignoreBasicTypesRef;
    return this;
  }

  /** Whether ignore string shared reference. */
  public FuryBuilder ignoreStringRef(boolean ignoreStringRef) {
    this.stringRefIgnored = ignoreStringRef;
    return this;
  }

  /**
   * Whether ignore reference tracking of all time types registered in {@link TimeSerializers} when
   * ref tracking is enabled.
   *
   * @see Config#isTimeRefIgnored
   */
  public FuryBuilder ignoreTimeRef(boolean ignoreTimeRef) {
    this.timeRefIgnored = ignoreTimeRef;
    return this;
  }

  /** Use variable length encoding for int/long. */
  public FuryBuilder withNumberCompressed(boolean numberCompressed) {
    this.compressInt = numberCompressed;
    this.longEncoding = LongEncoding.SLI;
    return this;
  }

  /** Use variable length encoding for int. */
  public FuryBuilder withIntCompressed(boolean intCompressed) {
    this.compressInt = intCompressed;
    return this;
  }

  /** Use variable length encoding for long. */
  public FuryBuilder withLongCompressed(boolean longCompressed) {
    return withLongCompressed(longCompressed ? LongEncoding.SLI : LongEncoding.LE_RAW_BYTES);
  }

  /** Use variable length encoding for long. */
  public FuryBuilder withLongCompressed(LongEncoding longEncoding) {
    this.longEncoding = longEncoding;
    return this;
  }

  /** Whether compress string for small size. */
  public FuryBuilder withStringCompressed(boolean stringCompressed) {
    this.compressString = stringCompressed;
    return this;
  }

  /**
   * Set classloader for fury to load classes, this classloader can't up updated. Fury will cache
   * the class meta data, if classloader can be updated, there may be class meta collision if
   * different classloaders have classes with same name.
   *
   * <p>If you want to change classloader, please use {@link io.fury.util.LoaderBinding} or {@link
   * ThreadSafeFury} to setup mapping between classloaders and fury instances.
   */
  public FuryBuilder withClassLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
    return this;
  }

  /**
   * Set class schema compatible mode.
   *
   * @see CompatibleMode
   */
  public FuryBuilder withCompatibleMode(CompatibleMode compatibleMode) {
    this.compatibleMode = compatibleMode;
    return this;
  }

  /**
   * Whether check class schema consistency, will be disabled automatically when {@link
   * CompatibleMode#COMPATIBLE} is enabled. Do not disable this option unless you can ensure the
   * class won't evolve.
   */
  public FuryBuilder withClassVersionCheck(boolean checkClassVersion) {
    this.checkClassVersion = checkClassVersion;
    return this;
  }

  /** Whether check classes under `java.*` implement {@link java.io.Serializable}. */
  public FuryBuilder withJdkClassSerializableCheck(boolean checkJdkClassSerializable) {
    this.checkJdkClassSerializable = checkJdkClassSerializable;
    return this;
  }

  /**
   * Whether pre-register guava types such as `RegularImmutableMap`/`RegularImmutableList`. Those
   * types are not public API, but seems pretty stable.
   *
   * @see io.fury.serializer.GuavaSerializers
   */
  public FuryBuilder registerGuavaTypes(boolean register) {
    this.registerGuavaTypes = register;
    return this;
  }

  /**
   * Whether to require registering classes for serialization, enabled by default. If disabled,
   * unknown classes can be deserialized, which may be insecure and cause remote code execution
   * attack if the classes `constructor`/`equals`/`hashCode` method contain malicious code. Do not
   * disable class registration if you can't ensure your environment are *indeed secure*. We are not
   * responsible for security risks if you disable this option. If you disable this option, you can
   * configure {@link io.fury.resolver.ClassChecker} by {@link ClassResolver#setClassChecker} to
   * control which classes are allowed being serialized.
   */
  public FuryBuilder requireClassRegistration(boolean requireClassRegistration) {
    this.requireClassRegistration = requireClassRegistration;
    return this;
  }

  /** Whether to enable meta share mode. */
  public FuryBuilder withMetaContextShare(boolean shareMetaContext) {
    this.shareMetaContext = shareMetaContext;
    return this;
  }

  /**
   * Whether deserialize/skip data of un-existed class.
   *
   * @see Config#deserializeUnexistedClass()
   */
  public FuryBuilder withDeserializeUnexistedClass(boolean deserializeUnexistedClass) {
    this.deserializeUnexistedClass = deserializeUnexistedClass;
    return this;
  }

  /**
   * Whether enable jit for serialization. When disabled, the first serialization will be faster
   * since no need to generate code, but later will be much slower compared jit mode.
   */
  public FuryBuilder withCodegen(boolean codeGen) {
    this.codeGenEnabled = codeGen;
    return this;
  }

  /**
   * Whether enable async compilation. If enabled, serialization will use interpreter mode
   * serialization first and switch to jit serialization after async serializer jit for a class \ is
   * finished.
   *
   * @see Config#isAsyncCompilationEnabled()
   */
  public FuryBuilder withAsyncCompilation(boolean asyncCompilation) {
    this.asyncCompilationEnabled = asyncCompilation;
    return this;
  }

  private void finish() {
    if (classLoader == null) {
      classLoader = Thread.currentThread().getContextClassLoader();
    }
    if (language != Language.JAVA) {
      stringRefIgnored = false;
    }
    if (ENABLE_CLASS_REGISTRATION_FORCIBLY) {
      if (!requireClassRegistration) {
        LOG.warn("Class registration is enabled forcibly.");
        requireClassRegistration = true;
      }
    }
    if (defaultJDKStreamSerializerType == JavaSerializer.class) {
      LOG.warn(
          "JDK serialization is used for types which customized java serialization by "
              + "implementing methods such as writeObject/readObject. This is not secure, try to "
              + "use {} instead, or implement a custom {}.",
          ObjectStreamSerializer.class,
          Serializer.class);
    }
    if (compatibleMode == CompatibleMode.COMPATIBLE) {
      checkClassVersion = false;
    }
    if (!requireClassRegistration) {
      LOG.warn(
          "Class registration isn't forced, unknown classes can be deserialized. "
              + "If the environment isn't secure, please enable class registration by "
              + "`FuryBuilder#requireClassRegistration(true)` or configure ClassChecker by "
              + "`ClassResolver#setClassChecker`");
    }
  }

  /**
   * Create Fury and print exception when failed. Many application will create fury as a static
   * variable, Fury creation exception will be swallowed by {@link NoClassDefFoundError}. We print
   * exception explicitly for better debugging.
   */
  private static Fury newFury(FuryBuilder builder, ClassLoader classLoader) {
    try {
      return new Fury(builder, classLoader);
    } catch (Throwable t) {
      t.printStackTrace();
      LOG.error("Fury creation failed with classloader {}", classLoader);
      Platform.throwException(t);
      throw new RuntimeException(t);
    }
  }

  public Fury build() {
    finish();
    ClassLoader loader = this.classLoader;
    // clear classLoader to avoid `LoaderBinding#furyFactory` lambda capture classLoader by
    // capturing `FuryBuilder`, which make `classLoader` not able to be gc.
    this.classLoader = null;
    return newFury(this, loader);
  }

  /** Build thread safe fury. */
  public ThreadSafeFury buildThreadSafeFury() {
    return buildThreadLocalFury();
  }

  /** Build thread safe fury backed by {@link ThreadLocalFury}. */
  public ThreadLocalFury buildThreadLocalFury() {
    finish();
    ClassLoader loader = this.classLoader;
    // clear classLoader to avoid `LoaderBinding#furyFactory` lambda capture classLoader by
    // capturing `FuryBuilder`,  which make `classLoader` not able to be gc.
    this.classLoader = null;
    ThreadLocalFury threadSafeFury = new ThreadLocalFury(classLoader -> newFury(this, classLoader));
    threadSafeFury.setClassLoader(loader);
    return threadSafeFury;
  }

  /**
   * Build pooled ThreadSafeFury.
   *
   * @param minPoolSize min pool size
   * @param maxPoolSize max pool size
   * @return ThreadSafeFuryPool
   */
  public ThreadSafeFury buildThreadSafeFuryPool(int minPoolSize, int maxPoolSize) {
    return buildThreadSafeFuryPool(minPoolSize, maxPoolSize, 30L, TimeUnit.SECONDS);
  }

  /**
   * Build pooled ThreadSafeFury.
   *
   * @param minPoolSize min pool size
   * @param maxPoolSize max pool size
   * @param expireTime cache expire time, default 5's
   * @param timeUnit TimeUnit, default SECONDS
   * @return ThreadSafeFuryPool
   */
  public ThreadSafeFury buildThreadSafeFuryPool(
      int minPoolSize, int maxPoolSize, long expireTime, TimeUnit timeUnit) {
    if (minPoolSize < 0 || maxPoolSize < 0 || minPoolSize > maxPoolSize) {
      throw new IllegalArgumentException(
          String.format(
              "thread safe fury pool's init pool size error, please check it, min:[%s], max:[%s]",
              minPoolSize, maxPoolSize));
    }
    finish();
    ClassLoader loader = this.classLoader;
    this.classLoader = null;
    ThreadSafeFury threadSafeFury =
        new ThreadPoolFury(
            classLoader -> newFury(this, classLoader),
            minPoolSize,
            maxPoolSize,
            expireTime,
            timeUnit);
    threadSafeFury.setClassLoader(loader);
    return threadSafeFury;
  }
}