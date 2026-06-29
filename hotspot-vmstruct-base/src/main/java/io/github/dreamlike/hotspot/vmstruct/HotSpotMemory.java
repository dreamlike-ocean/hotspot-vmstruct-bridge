package io.github.dreamlike.hotspot.vmstruct;

import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static java.nio.charset.StandardCharsets.US_ASCII;

public final class HotSpotMemory {
    public static final Linker LINKER = Linker.nativeLinker();
    public static final long ADDRESS_SIZE = ValueLayout.ADDRESS.byteSize();

    private HotSpotMemory() {
    }

    public static MemorySegment memory(long address, long byteSize) {
        return MemorySegment.ofAddress(address).reinterpret(byteSize);
    }

    public static long getAddress(long address) {
        return memory(address, ADDRESS_SIZE).get(ValueLayout.ADDRESS, 0).address();
    }

    public static void putAddress(long address, long value) {
        memory(address, ADDRESS_SIZE).set(
                ValueLayout.ADDRESS,
                0,
                value == 0 ? MemorySegment.NULL : MemorySegment.ofAddress(value));
    }

    public static int getInt(long address) {
        return memory(address, Integer.BYTES).get(ValueLayout.JAVA_INT, 0);
    }

    public static long getLong(long address) {
        return memory(address, Long.BYTES).get(ValueLayout.JAVA_LONG, 0);
    }

    public static short getShort(long address) {
        return memory(address, Short.BYTES).get(ValueLayout.JAVA_SHORT, 0);
    }

    public static byte getByte(long address) {
        return memory(address, Byte.BYTES).get(ValueLayout.JAVA_BYTE, 0);
    }

    public static int getUnsignedByte(long address) {
        return Byte.toUnsignedInt(getByte(address));
    }

    public static int getUnsignedShort(long address) {
        return Short.toUnsignedInt(getShort(address));
    }

    public static long getUnsignedInt(long address) {
        return Integer.toUnsignedLong(getInt(address));
    }

    public static String cString(long address) {
        return MemorySegment.ofAddress(address)
                .reinterpret(Long.MAX_VALUE)
                .getString(0, US_ASCII);
    }
}
