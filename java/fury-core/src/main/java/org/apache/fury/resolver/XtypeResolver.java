package org.apache.fury.resolver;

import static org.apache.fury.Fury.NOT_SUPPORT_XLANG;
import static org.apache.fury.meta.Encoders.GENERIC_ENCODER;
import static org.apache.fury.meta.Encoders.PACKAGE_DECODER;
import static org.apache.fury.meta.Encoders.PACKAGE_ENCODER;
import static org.apache.fury.meta.Encoders.TYPE_NAME_DECODER;
import static org.apache.fury.resolver.ClassResolver.NO_CLASS_ID;
import static org.apache.fury.type.TypeUtils.qualifiedName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.fury.Fury;
import org.apache.fury.collection.IdentityMap;
import org.apache.fury.collection.LongMap;
import org.apache.fury.collection.ObjectMap;
import org.apache.fury.config.Config;
import org.apache.fury.exception.ClassUnregisteredException;
import org.apache.fury.exception.SerializerUnregisteredException;
import org.apache.fury.logging.Logger;
import org.apache.fury.logging.LoggerFactory;
import org.apache.fury.memory.MemoryBuffer;
import org.apache.fury.memory.Platform;
import org.apache.fury.meta.Encoders;
import org.apache.fury.meta.MetaString;
import org.apache.fury.reflect.ReflectionUtils;
import org.apache.fury.serializer.ArraySerializers.ObjectArraySerializer;
import org.apache.fury.serializer.EnumSerializer;
import org.apache.fury.serializer.NonexistentClass;
import org.apache.fury.serializer.NonexistentClassSerializers;
import org.apache.fury.serializer.Serializer;
import org.apache.fury.serializer.Serializers;
import org.apache.fury.serializer.StructSerializer;
import org.apache.fury.serializer.collection.CollectionSerializer;
import org.apache.fury.serializer.collection.MapSerializer;
import org.apache.fury.type.TypeUtils;
import org.apache.fury.type.Types;
import org.apache.fury.util.Preconditions;

@SuppressWarnings({"unchecked", "rawtypes"})
// TODO(chaokunyang) Abstract type resolver for java/xlang type resolution.
public class XtypeResolver {
  private static final Logger LOG = LoggerFactory.getLogger(XtypeResolver.class);

  private static final float loadFactor = 0.5f;
  // Most systems won't have so many types for serialization.
  private static final int MAX_TYPE_ID = 4096;

  private final Config config;
  private final Fury fury;
  private final ClassResolver classResolver;
  private final ClassInfoHolder classInfoCache = new ClassInfoHolder(ClassResolver.NIL_CLASS_INFO);
  private final MetaStringResolver metaStringResolver;
  // IdentityMap has better lookup performance, when loadFactor is 0.05f, performance is better
  private final IdentityMap<Class<?>, ClassInfo> classInfoMap = new IdentityMap<>(64, loadFactor);
  // Every deserialization for unregistered class will query it, performance is important.
  private final ObjectMap<ClassNameBytes, ClassInfo> compositeClassNameBytes2ClassInfo =
      new ObjectMap<>(16, loadFactor);
  private final ObjectMap<String, ClassInfo> qualifiedType2ClassInfo =
      new ObjectMap<>(16, loadFactor);
  private int xtypeIdGenerator = 64;

  // Use ClassInfo[] or LongMap?
  // ClassInfo[] is faster, but we can't have bigger type id.
  private final LongMap<ClassInfo> xtypeIdToClassMap = new LongMap<>(8, loadFactor);
  private final Set<Integer> registeredTypeIds = new HashSet<>();

  public XtypeResolver(Fury fury) {
    this.config = fury.getConfig();
    this.fury = fury;
    this.classResolver = fury.getClassResolver();
    this.metaStringResolver = fury.getMetaStringResolver();
    registerDefaultTypes();
  }

  public void register(Class<?> type) {
    while (registeredTypeIds.contains(xtypeIdGenerator)) {
      xtypeIdGenerator++;
    }
    register(type, xtypeIdGenerator++);
  }

  public void register(Class<?> type, int typeId) {
    // ClassInfo[] has length of max type id. If the type id is too big, Fury will waste many
    // memory.
    // We can relax this limit in the future.
    Preconditions.checkArgument(typeId < MAX_TYPE_ID, "Too big type id %s", typeId);
    ClassInfo classInfo = classInfoMap.get(type);
    Serializer<?> serializer = null;
    if (classInfo != null) {
      serializer = classInfo.serializer;
      if (classInfo.xtypeId != 0) {
        throw new IllegalArgumentException(
            String.format("Type %s has been registered with id %s", type, classInfo.xtypeId));
      }
      String prevNamespace = decodeNamespace(classInfo.packageNameBytes);
      String prevTypeName = decodeTypeName(classInfo.classNameBytes);
      if (!type.getSimpleName().equals(prevTypeName)) {
        throw new IllegalArgumentException(
            String.format(
                "Type %s has been registered with namespace %s type %s",
                type, prevNamespace, prevTypeName));
      }
    }
    int xtypeId = typeId;
    if (type.isEnum()) {
      xtypeId = xtypeId << 8 + Types.ENUM;

    } else {
      if (serializer != null) {
        if (serializer instanceof StructSerializer) {
          xtypeId = xtypeId << 8 + Types.STRUCT;
        } else {
          xtypeId = xtypeId << 8 + Types.EXT;
        }
      }
    }
    register(
        type,
        serializer,
        ReflectionUtils.getPackage(type),
        ReflectionUtils.getClassNameWithoutPackage(type),
        xtypeId);
  }

  public void register(Class<?> type, String namespace, String typeName) {
    Preconditions.checkArgument(
        !typeName.contains("."),
        "Typename %s should not contains `.`, please put it into namespace",
        typeName);
    ClassInfo classInfo = classInfoMap.get(type);
    Serializer<?> serializer = null;
    if (classInfo != null) {
      serializer = classInfo.serializer;
      if (classInfo.classNameBytes != null) {
        String prevNamespace = decodeNamespace(classInfo.packageNameBytes);
        String prevTypeName = decodeTypeName(classInfo.classNameBytes);
        if (!namespace.equals(prevNamespace) || typeName.equals(prevTypeName)) {
          throw new IllegalArgumentException(
              String.format(
                  "Type %s has been registered with namespace %s type %s",
                  type, prevNamespace, prevTypeName));
        }
      }
    }
    short xtypeId;
    if (serializer != null) {
      if (serializer instanceof StructSerializer) {
        xtypeId = Types.NS_STRUCT;
      } else if (serializer instanceof EnumSerializer) {
        xtypeId = Types.NS_ENUM;
      } else {
        xtypeId = Types.NS_EXT;
      }
    } else {
      if (type.isEnum()) {
        xtypeId = Types.NS_ENUM;
      } else {
        xtypeId = Types.NS_STRUCT;
      }
    }
    register(type, serializer, namespace, typeName, xtypeId);
  }

  private void register(
      Class<?> type, Serializer<?> serializer, String namespace, String typeName, int xtypeId) {
    ClassInfo classInfo = newClassInfo(type, serializer, namespace, typeName, (short) xtypeId);
    qualifiedType2ClassInfo.put(qualifiedName(namespace, typeName), classInfo);
    if (serializer == null) {
      if (type.isEnum()) {
        classInfo.serializer = new EnumSerializer(fury, (Class<Enum>) type);
      } else {
        classInfo.serializer = new StructSerializer(fury, type);
      }
    }
    classInfoMap.put(type, classInfo);
    registeredTypeIds.add(xtypeId);
    xtypeIdToClassMap.put(xtypeId, classInfo);
  }

  private ClassInfo newClassInfo(Class<?> type, Serializer<?> serializer, short xtypeId) {
    return newClassInfo(
        type,
        serializer,
        ReflectionUtils.getPackage(type),
        ReflectionUtils.getClassNameWithoutPackage(type),
        xtypeId);
  }

  private ClassInfo newClassInfo(
      Class<?> type, Serializer<?> serializer, String namespace, String typeName, short xtypeId) {
    MetaStringBytes fullClassNameBytes =
        metaStringResolver.getOrCreateMetaStringBytes(
            GENERIC_ENCODER.encode(type.getName(), MetaString.Encoding.UTF_8));
    MetaStringBytes nsBytes =
        metaStringResolver.getOrCreateMetaStringBytes(Encoders.encodePackage(namespace));
    MetaStringBytes classNameBytes =
        metaStringResolver.getOrCreateMetaStringBytes(Encoders.encodeTypeName(typeName));
    return new ClassInfo(
        type, fullClassNameBytes, nsBytes, classNameBytes, false, serializer, NO_CLASS_ID, xtypeId);
  }

  private String decodeNamespace(MetaStringBytes packageNameBytes) {
    return packageNameBytes.decode(PACKAGE_DECODER);
  }

  private String decodeTypeName(MetaStringBytes classNameBytes) {
    return classNameBytes.decode(TYPE_NAME_DECODER);
  }

  public <T> void registerSerializer(Class<T> type, Class<? extends Serializer> serializerClass) {
    ClassInfo classInfo = checkClassRegistration(type);
    classInfo.serializer = Serializers.newSerializer(fury, type, serializerClass);
  }

  public void registerSerializer(Class<?> type, Serializer<?> serializer) {
    ClassInfo classInfo = checkClassRegistration(type);
    classInfo.serializer = serializer;
  }

  private ClassInfo checkClassRegistration(Class<?> type) {
    ClassInfo classInfo = classInfoMap.get(type);
    Preconditions.checkArgument(
        classInfo != null
            && (classInfo.xtypeId != 0
                || !type.getSimpleName().equals(decodeTypeName(classInfo.classNameBytes))),
        "Type %s should be registered with id or namespace+typename before register serializer",
        type);
    return classInfo;
  }

  public ClassInfo getClassInfo(Class<?> cls, ClassInfoHolder classInfoHolder) {
    ClassInfo classInfo = classInfoHolder.classInfo;
    if (classInfo.getCls() != cls) {
      classInfo = classInfoMap.get(cls);
      if (classInfo == null) {
        classInfo = buildClassInfo(cls);
      }
      classInfoHolder.classInfo = classInfo;
    }
    assert classInfo.serializer != null;
    return classInfo;
  }

  private ClassInfo buildClassInfo(Class<?> cls) {
    Serializer serializer;
    int xtypeId;
    if (classResolver.isSet(cls)) {
      serializer = new CollectionSerializer(fury, cls);
      xtypeId = Types.SET;
    } else if (classResolver.isCollection(cls)) {
      serializer = new CollectionSerializer(fury, cls);
      xtypeId = Types.LIST;
    } else if (cls.isArray() && !TypeUtils.getArrayComponent(cls).isPrimitive()) {
      serializer = new ObjectArraySerializer(fury, cls);
      xtypeId = Types.LIST;
    } else if (classResolver.isMap(cls)) {
      serializer = new MapSerializer(fury, cls);
      xtypeId = Types.MAP;
    } else {
      throw new ClassUnregisteredException(cls);
    }
    ClassInfo info = newClassInfo(cls, serializer, (short) xtypeId);
    classInfoMap.put(cls, info);
    return info;
  }

  private void registerDefaultTypes() {
    registerDefaultTypes(Types.BOOL, Boolean.class, boolean.class, AtomicBoolean.class);
    registerDefaultTypes(Types.INT8, Byte.class, byte.class);
    registerDefaultTypes(Types.INT16, Short.class, short.class);
    registerDefaultTypes(Types.INT32, Integer.class, int.class, AtomicInteger.class);
    registerDefaultTypes(Types.INT64, Long.class, long.class, AtomicLong.class);
    registerDefaultTypes(Types.FLOAT32, Float.class, float.class);
    registerDefaultTypes(Types.FLOAT64, Double.class, double.class);
    registerDefaultTypes(Types.STRING, String.class);
    registerDefaultTypes(Types.DURATION, Duration.class);
    registerDefaultTypes(
        Types.TIMESTAMP, Instant.class, Date.class, Timestamp.class, LocalDateTime.class);
    registerDefaultTypes(Types.DECIMAL, BigDecimal.class, BigInteger.class);
    registerDefaultTypes(
        Types.BINARY,
        byte[].class,
        Platform.HEAP_BYTE_BUFFER_CLASS,
        Platform.DIRECT_BYTE_BUFFER_CLASS);
    registerDefaultTypes(Types.BOOL_ARRAY, boolean[].class);
    registerDefaultTypes(Types.INT16_ARRAY, short[].class);
    registerDefaultTypes(Types.INT32_ARRAY, int[].class);
    registerDefaultTypes(Types.INT64_ARRAY, long[].class);
    registerDefaultTypes(Types.FLOAT32_ARRAY, float[].class);
    registerDefaultTypes(Types.FLOAT64_ARRAY, double[].class);
    registerDefaultTypes(Types.LIST, ArrayList.class, Object[].class);
    registerDefaultTypes(Types.SET, HashSet.class, LinkedHashSet.class);
    registerDefaultTypes(Types.MAP, HashMap.class, LinkedHashMap.class);
  }

  private void registerDefaultTypes(int xtypeId, Class<?> defaultType, Class<?>... otherTypes) {
    ClassInfo classInfo =
        newClassInfo(defaultType, classResolver.getSerializer(defaultType), (short) xtypeId);
    classInfoMap.put(defaultType, classInfo);
    xtypeIdToClassMap.put(xtypeId, classInfo);
    for (Class<?> otherType : otherTypes) {
      classInfo = newClassInfo(otherType, classResolver.getSerializer(otherType), (short) xtypeId);
      classInfoMap.put(otherType, classInfo);
    }
  }

  public ClassInfo writeClassInfo(MemoryBuffer buffer, Object obj) {
    ClassInfo classInfo = getClassInfo(obj.getClass(), classInfoCache);
    int xtypeId = classInfo.getXtypeId();
    byte internalTypeId = (byte) xtypeId;
    buffer.writeVarUint32Small7(xtypeId);
    switch (internalTypeId) {
      case Types.NS_ENUM:
      case Types.NS_STRUCT:
      case Types.NS_EXT:
        assert classInfo.packageNameBytes != null;
        metaStringResolver.writeMetaStringBytes(buffer, classInfo.packageNameBytes);
        assert classInfo.classNameBytes != null;
        metaStringResolver.writeMetaStringBytes(buffer, classInfo.classNameBytes);
        break;
    }
    return classInfo;
  }

  public ClassInfo readClassInfo(MemoryBuffer buffer) {
    long xtypeId = buffer.readVarUint32Small14();
    byte internalTypeId = (byte) xtypeId;
    switch (internalTypeId) {
      case Types.NS_ENUM:
      case Types.NS_STRUCT:
      case Types.NS_COMPATIBLE_STRUCT:
      case Types.NS_EXT:
        MetaStringBytes packageBytes = metaStringResolver.readMetaStringBytes(buffer);
        MetaStringBytes simpleClassNameBytes = metaStringResolver.readMetaStringBytes(buffer);
        return loadBytesToClassInfo(internalTypeId, packageBytes, simpleClassNameBytes);
      default:
        return xtypeIdToClassMap.get(xtypeId);
    }
  }

  private ClassInfo loadBytesToClassInfo(
      int internalTypeId, MetaStringBytes packageBytes, MetaStringBytes simpleClassNameBytes) {
    ClassNameBytes classNameBytes =
        new ClassNameBytes(packageBytes.hashCode, simpleClassNameBytes.hashCode);
    ClassInfo classInfo = compositeClassNameBytes2ClassInfo.get(classNameBytes);
    if (classInfo == null) {
      classInfo =
          populateBytesToClassInfo(
              internalTypeId, classNameBytes, packageBytes, simpleClassNameBytes);
    }
    return classInfo;
  }

  private ClassInfo populateBytesToClassInfo(
      int typeId,
      ClassNameBytes classNameBytes,
      MetaStringBytes packageBytes,
      MetaStringBytes simpleClassNameBytes) {
    String namespace = packageBytes.decode(PACKAGE_DECODER);
    String typeName = simpleClassNameBytes.decode(TYPE_NAME_DECODER);
    String qualifiedName = qualifiedName(namespace, typeName);
    ClassInfo classInfo = qualifiedType2ClassInfo.get(qualifiedName);
    if (classInfo == null) {
      String msg = String.format("Class %s not registered", qualifiedName);
      Class<?> type = null;
      if (config.deserializeNonexistentClass()) {
        LOG.warn(msg);
        switch (typeId) {
          case Types.NS_ENUM:
          case Types.NS_STRUCT:
          case Types.NS_COMPATIBLE_STRUCT:
            type =
                NonexistentClass.getNonexistentClass(
                    qualifiedName, isEnum(typeId), 0, config.isMetaShareEnabled());
            break;
          case Types.NS_EXT:
            throw new SerializerUnregisteredException(qualifiedName);
        }
      } else {
        throw new ClassUnregisteredException(qualifiedName);
      }
      MetaStringBytes fullClassNameBytes =
          metaStringResolver.getOrCreateMetaStringBytes(
              PACKAGE_ENCODER.encode(qualifiedName, MetaString.Encoding.UTF_8));
      classInfo =
          new ClassInfo(
              type,
              fullClassNameBytes,
              packageBytes,
              simpleClassNameBytes,
              false,
              null,
              NO_CLASS_ID,
              NOT_SUPPORT_XLANG);
      if (NonexistentClass.class.isAssignableFrom(TypeUtils.getComponentIfArray(type))) {
        classInfo.serializer = NonexistentClassSerializers.getSerializer(fury, qualifiedName, type);
      }
    }
    compositeClassNameBytes2ClassInfo.put(classNameBytes, classInfo);
    return classInfo;
  }

  private boolean isEnum(int internalTypeId) {
    return internalTypeId == Types.ENUM || internalTypeId == Types.NS_ENUM;
  }
}