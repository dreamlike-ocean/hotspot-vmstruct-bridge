package io.github.dreamlike.hotspot.vmstruct;

import java.util.OptionalInt;

public final class VmStructs {
    private final long entries;
    private final long typeNameOffset;
    private final long fieldNameOffset;
    private final long isStaticOffset;
    private final long offsetOffset;
    private final long addressOffset;
    private final long stride;
    private final long typeEntries;
    private final long typeEntryTypeNameOffset;
    private final long typeEntrySizeOffset;
    private final long typeEntryStride;
    private final long intConstantEntries;
    private final long intConstantNameOffset;
    private final long intConstantValueOffset;
    private final long intConstantStride;

    private VmStructs() {
        entries = HotSpotMemory.getAddress(symbol("gHotSpotVMStructs"));
        typeNameOffset = HotSpotMemory.getLong(symbol("gHotSpotVMStructEntryTypeNameOffset"));
        fieldNameOffset = HotSpotMemory.getLong(symbol("gHotSpotVMStructEntryFieldNameOffset"));
        isStaticOffset = HotSpotMemory.getLong(symbol("gHotSpotVMStructEntryIsStaticOffset"));
        offsetOffset = HotSpotMemory.getLong(symbol("gHotSpotVMStructEntryOffsetOffset"));
        addressOffset = HotSpotMemory.getLong(symbol("gHotSpotVMStructEntryAddressOffset"));
        stride = HotSpotMemory.getLong(symbol("gHotSpotVMStructEntryArrayStride"));
        typeEntries = HotSpotMemory.getAddress(symbol("gHotSpotVMTypes"));
        typeEntryTypeNameOffset = HotSpotMemory.getLong(symbol("gHotSpotVMTypeEntryTypeNameOffset"));
        typeEntrySizeOffset = HotSpotMemory.getLong(symbol("gHotSpotVMTypeEntrySizeOffset"));
        typeEntryStride = HotSpotMemory.getLong(symbol("gHotSpotVMTypeEntryArrayStride"));
        intConstantEntries = HotSpotMemory.getAddress(symbol("gHotSpotVMIntConstants"));
        intConstantNameOffset = HotSpotMemory.getLong(symbol("gHotSpotVMIntConstantEntryNameOffset"));
        intConstantValueOffset = HotSpotMemory.getLong(symbol("gHotSpotVMIntConstantEntryValueOffset"));
        intConstantStride = HotSpotMemory.getLong(symbol("gHotSpotVMIntConstantEntryArrayStride"));
    }

    public static VmStructs current() {
        return Holder.INSTANCE;
    }

    public long offset(String typeName, String fieldName) {
        long entry = find(typeName, fieldName, false);
        return HotSpotMemory.getLong(entry + offsetOffset);
    }

    public long staticAddress(String typeName, String fieldName) {
        long entry = find(typeName, fieldName, true);
        return HotSpotMemory.getAddress(entry + addressOffset);
    }

    public long typeSize(String typeName) {
        long entry = findType(typeName);
        return HotSpotMemory.getLong(entry + typeEntrySizeOffset);
    }

    public int intConstant(String name) {
        return optionalIntConstant(name)
                .orElseThrow(() -> new IllegalArgumentException("VMIntConstant not found: " + name));
    }

    public OptionalInt optionalIntConstant(String name) {
        for (long entry = intConstantEntries; ; entry += intConstantStride) {
            long namePtr = HotSpotMemory.getAddress(entry + intConstantNameOffset);
            if (namePtr == 0) {
                return OptionalInt.empty();
            }
            if (name.equals(HotSpotMemory.cString(namePtr))) {
                return OptionalInt.of(HotSpotMemory.getInt(entry + intConstantValueOffset));
            }
        }
    }

    private long find(String typeName, String fieldName, boolean isStatic) {
        // VMStructEntry 本质上是 HotSpot 给“外部观察者”导出的 C++ 字段元数据表。每条记录描述一个 VM 内部字段
        for (long entry = entries; ; entry += stride) {
            long typePtr = HotSpotMemory.getAddress(entry + typeNameOffset);
            long fieldPtr = HotSpotMemory.getAddress(entry + fieldNameOffset);
            if (typePtr == 0 || fieldPtr == 0) {
                break;
            }
            if ((HotSpotMemory.getInt(entry + isStaticOffset) != 0) == isStatic
                    && typeName.equals(HotSpotMemory.cString(typePtr))
                    && fieldName.equals(HotSpotMemory.cString(fieldPtr))) {
                return entry;
            }
        }
        throw new IllegalArgumentException("VMStruct not found: " + typeName + "::" + fieldName);
    }

    private long findType(String typeName) {
        for (long entry = typeEntries; ; entry += typeEntryStride) {
            long typePtr = HotSpotMemory.getAddress(entry + typeEntryTypeNameOffset);
            if (typePtr == 0) {
                break;
            }
            if (typeName.equals(HotSpotMemory.cString(typePtr))) {
                return entry;
            }
        }
        throw new IllegalArgumentException("VMType not found: " + typeName);
    }

    public static long symbol(String name) {
        return HotSpotLibrary.runtimeAddress(name);
    }

    private static final class Holder {
        private static final VmStructs INSTANCE = new VmStructs();
    }
}
