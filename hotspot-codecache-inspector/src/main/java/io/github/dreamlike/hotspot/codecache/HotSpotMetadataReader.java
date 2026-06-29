package io.github.dreamlike.hotspot.codecache;

import io.github.dreamlike.hotspot.vmstruct.HotSpotMemory;
import io.github.dreamlike.hotspot.vmstruct.VmStructs;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

final class HotSpotMetadataReader {
    private final long methodConstMethodOffset;
    private final long constMethodConstantsOffset;
    private final long constMethodNameIndexOffset;
    private final long constMethodSignatureIndexOffset;
    private final long constantPoolHolderOffset;
    private final long constantPoolSize;
    private final long klassNameOffset;
    private final long symbolLengthOffset;
    private final long symbolBodyOffset;

    private HotSpotMetadataReader() {
        VmStructs vm = VmStructs.current();
        methodConstMethodOffset = vm.offset("Method", "_constMethod");
        constMethodConstantsOffset = vm.offset("ConstMethod", "_constants");
        constMethodNameIndexOffset = vm.offset("ConstMethod", "_name_index");
        constMethodSignatureIndexOffset = vm.offset("ConstMethod", "_signature_index");
        constantPoolHolderOffset = vm.offset("ConstantPool", "_pool_holder");
        constantPoolSize = vm.typeSize("ConstantPool");
        klassNameOffset = vm.offset("Klass", "_name");
        symbolLengthOffset = vm.offset("Symbol", "_length");
        symbolBodyOffset = vm.offset("Symbol", "_body");
    }

    static HotSpotMetadataReader load() {
        return Holder.INSTANCE;
    }

    String methodSignature(long method) {
        if (method == 0) {
            return null;
        }
        long constMethod = HotSpotMemory.getAddress(method + methodConstMethodOffset);
        if (constMethod == 0) {
            return null;
        }
        long constants = HotSpotMemory.getAddress(constMethod + constMethodConstantsOffset);
        if (constants == 0) {
            return null;
        }

        int nameIndex = HotSpotMemory.getUnsignedShort(constMethod + constMethodNameIndexOffset);
        int signatureIndex = HotSpotMemory.getUnsignedShort(constMethod + constMethodSignatureIndexOffset);
        String methodName = symbolString(symbolAt(constants, nameIndex));
        String methodDescriptor = symbolString(symbolAt(constants, signatureIndex));

        long holder = HotSpotMemory.getAddress(constants + constantPoolHolderOffset);
        String holderName = holder == 0 ? null : symbolString(HotSpotMemory.getAddress(holder + klassNameOffset));
        if (holderName != null) {
            holderName = holderName.replace('/', '.');
        }
        if (holderName == null && methodName == null && methodDescriptor == null) {
            return null;
        }
        return nullToUnknown(holderName) + "::" + nullToUnknown(methodName) + nullToUnknown(methodDescriptor);
    }

    private long symbolAt(long constantPool, int index) {
        return HotSpotMemory.getAddress(constantPool + constantPoolSize + (long) index * HotSpotMemory.ADDRESS_SIZE);
    }

    private String symbolString(long symbol) {
        if (symbol == 0) {
            return null;
        }
        int length = HotSpotMemory.getUnsignedShort(symbol + symbolLengthOffset);
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = HotSpotMemory.getByte(symbol + symbolBodyOffset + i);
        }
        return decodeModifiedUtf8(bytes);
    }

    private static String decodeModifiedUtf8(byte[] bytes) {
        if (bytes.length > 0xFFFF) {
            return new String(bytes, UTF_8);
        }
        byte[] prefixed = new byte[bytes.length + Short.BYTES];
        prefixed[0] = (byte) (bytes.length >>> Byte.SIZE);
        prefixed[1] = (byte) bytes.length;
        System.arraycopy(bytes, 0, prefixed, Short.BYTES, bytes.length);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(prefixed))) {
            return input.readUTF();
        } catch (IOException | RuntimeException e) {
            return new String(bytes, UTF_8);
        }
    }

    private static String nullToUnknown(String value) {
        return value == null ? "<unknown>" : value;
    }

    private static final class Holder {
        private static final HotSpotMetadataReader INSTANCE = new HotSpotMetadataReader();
    }
}
