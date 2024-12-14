#include <cstdint> // For fixed-width integer types

namespace fury {
enum class TypeId : int32_t {
  // Fury added type for cross-language serialization.
  BOOL = 1,
  INT8 = 2,
  INT16 = 3,
  INT32 = 4,
  VAR_INT32 = 5,
  INT64 = 6,
  VAR_INT64 = 7,
  SLI_INT64 = 8,
  FLOAT16 = 9,
  FLOAT32 = 10,
  FLOAT64 = 11,
  STRING = 12,
  ENUM = 13,
  NS_ENUM = 14,
  STRUCT = 15,
  POLYMORPHIC_STRUCT = 16,
  COMPATIBLE_STRUCT = 17,
  POLYMORPHIC_COMPATIBLE_STRUCT = 18,
  NS_STRUCT = 19,
  NS_POLYMORPHIC_STRUCT = 20,
  NS_COMPATIBLE_STRUCT = 21,
  NS_POLYMORPHIC_COMPATIBLE_STRUCT = 22,
  EXT = 23,
  POLYMORPHIC_EXT = 24,
  NS_EXT = 25,
  NS_POLYMORPHIC_EXT = 26,
  LIST = 27,
  SET = 28,
  MAP = 29,
  DURATION = 30,
  TIMESTAMP = 31,
  LOCAL_DATE = 32,
  DECIMAL = 33,
  BINARY = 34,
  ARRAY = 35,
  BOOL_ARRAY = 36,
  INT8_ARRAY = 37,
  INT16_ARRAY = 38,
  INT32_ARRAY = 39,
  INT64_ARRAY = 40,
  FLOAT16_ARRAY = 41,
  FLOAT32_ARRAY = 42,
  FLOAT64_ARRAY = 43,
  ARROW_RECORD_BATCH = 44,
  ARROW_TABLE = 45
};

inline bool IsNamespacedType(int32_t type_id) {
  switch (static_cast<TypeId>(type_id)) {
  case TypeId::NS_ENUM:
  case TypeId::NS_STRUCT:
  case TypeId::NS_POLYMORPHIC_STRUCT:
  case TypeId::NS_COMPATIBLE_STRUCT:
  case TypeId::NS_POLYMORPHIC_COMPATIBLE_STRUCT:
  case TypeId::NS_EXT:
  case TypeId::NS_POLYMORPHIC_EXT:
    return true;
  default:
    return false;
  }
}

} // namespace fury