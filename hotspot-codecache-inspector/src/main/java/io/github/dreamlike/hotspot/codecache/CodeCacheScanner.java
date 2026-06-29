package io.github.dreamlike.hotspot.codecache;

import io.github.dreamlike.hotspot.vmstruct.HotSpotMemory;

import java.util.ArrayList;

final class CodeCacheScanner {
    private static final int ABSENT_INT = Integer.MIN_VALUE;
    private static final int MAX_REASONABLE_CODE_BLOB_SIZE = 256 * 1024 * 1024;

    private final CodeCacheLayout layout;
    private final HotSpotMetadataReader metadataReader;

    private CodeCacheScanner() {
        layout = CodeCacheLayout.load();
        metadataReader = HotSpotMetadataReader.load();
    }

    static CodeCacheScanner load() {
        return Holder.INSTANCE;
    }

    CodeCacheEntry[] snapshot() {
        //CodeCache
        //    static GrowableArray<CodeHeap*>* _heaps
        //          |
        //          +--> CodeHeap 'profiled nmethods'
        //          +--> CodeHeap 'non-nmethods'
        //          +--> CodeHeap 'hot nmethods'
        //          +--> CodeHeap 'non-profiled nmethods'
        long heaps = HotSpotMemory.getAddress(layout.codeCacheHeapsAddress());
        if (heaps == 0) {
            return new CodeCacheEntry[0];
        }
        int length = HotSpotMemory.getInt(heaps + layout.growableArrayLengthOffset());
        if (length <= 0) {
            return new CodeCacheEntry[0];
        }
        long data = HotSpotMemory.getAddress(heaps + layout.growableArrayDataOffset());
        if (data == 0) {
            return new CodeCacheEntry[0];
        }

        ArrayList<CodeCacheEntry> result = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            long heap = HotSpotMemory.getAddress(data + (long) i * HotSpotMemory.ADDRESS_SIZE);
            if (heap != 0) {
                scanHeap(heap, result);
            }
        }
        return result.toArray(CodeCacheEntry[]::new);
    }

    private void scanHeap(long heap, ArrayList<CodeCacheEntry> result) {
        long begin = virtualSpaceLow(heap + layout.codeHeapMemoryOffset());
        long end = virtualSpaceHigh(heap + layout.codeHeapMemoryOffset());
        long segmentMap = virtualSpaceLow(heap + layout.codeHeapSegmentMapOffset());
        int log2SegmentSize = HotSpotMemory.getInt(heap + layout.codeHeapLog2SegmentSizeOffset());
        if (begin == 0 || end <= begin || segmentMap == 0 || log2SegmentSize < 0 || log2SegmentSize > 30) {
            return;
        }

        long ptr = begin;
        long lastBlob = 0;
        while (ptr < end) {
            long block = blockBase(ptr, begin, end, segmentMap, log2SegmentSize);
            if (block == 0) {
                return;
            }

            long blockLength = HotSpotMemory.getUnsignedInt(block + layout.heapBlockHeaderOffset() + layout.heapBlockHeaderLengthOffset());
            if (blockLength == 0) {
                return;
            }

            if (isBlockUsed(block)) {
                long blob = block + layout.heapBlockSize();
                if (blob != lastBlob) {
                    CodeCacheEntry entry = readEntry(blob);
                    if (entry != null) {
                        result.add(entry);
                        lastBlob = blob;
                    }
                }
            }

            long next = block + (blockLength << log2SegmentSize);
            if (next <= ptr) {
                return;
            }
            ptr = next;
        }
    }

    private long virtualSpaceLow(long virtualSpace) {
        return HotSpotMemory.getAddress(virtualSpace + layout.virtualSpaceLowOffset());
    }

    private long virtualSpaceHigh(long virtualSpace) {
        return HotSpotMemory.getAddress(virtualSpace + layout.virtualSpaceHighOffset());
    }

    private long blockBase(long pointer, long heapBegin, long heapEnd, long segmentMap, int log2SegmentSize) {
        if (pointer < heapBegin || pointer >= heapEnd) {
            return 0;
        }
        long segmentCount = (heapEnd - heapBegin) >> log2SegmentSize;
        long index = (pointer - heapBegin) >> log2SegmentSize;
        if (index < 0 || index >= segmentCount) {
            return 0;
        }

        int hop = HotSpotMemory.getUnsignedByte(segmentMap + index);
        if (hop == 0xFF) {
            return 0;
        }
        int guard = 0;
        while (hop > 0) {
            index -= hop;
            if (index < 0 || index >= segmentCount || guard++ > 4096) {
                return 0;
            }
            hop = HotSpotMemory.getUnsignedByte(segmentMap + index);
            if (hop == 0xFF) {
                return 0;
            }
        }
        return heapBegin + (index << log2SegmentSize);
    }

    private boolean isBlockUsed(long block) {
        return HotSpotMemory.getUnsignedByte(block + layout.heapBlockHeaderOffset() + layout.heapBlockHeaderUsedOffset()) != 0;
    }

    private CodeCacheEntry readEntry(long blob) {
        int kindValue = HotSpotMemory.getUnsignedByte(blob + layout.codeBlobKindOffset());
        if (!layout.isKnownKind(kindValue)) {
            return null;
        }

        int size = HotSpotMemory.getInt(blob + layout.codeBlobSizeOffset());
        int codeOffset = HotSpotMemory.getInt(blob + layout.codeBlobCodeOffset());
        int dataOffset = HotSpotMemory.getInt(blob + layout.codeBlobDataOffset());
        if (size <= 0 || size > MAX_REASONABLE_CODE_BLOB_SIZE || codeOffset < 0 || dataOffset < codeOffset || dataOffset > size) {
            return null;
        }

        CodeBlobKind kind = layout.kind(kindValue);
        String name = readBlobName(blob);
        long codeBegin = blob + codeOffset;
        long codeEnd = blob + dataOffset;
        int codeLength = dataOffset - codeOffset;
        if (kind != CodeBlobKind.NMETHOD) {
            return new CodeCacheEntry(
                    blob,
                    kind,
                    name,
                    codeBegin,
                    codeEnd,
                    codeLength,
                    ABSENT_INT,
                    null,
                    null,
                    ABSENT_INT,
                    false,
                    null,
                    ABSENT_INT,
                    0,
                    null,
                    0,
                    0);
        }

        int stateValue = HotSpotMemory.getByte(blob + layout.nmethodStateOffset());
        int exceptionOffset = HotSpotMemory.getInt(blob + layout.nmethodExceptionOffset());
        long exceptionStubAddress = validBlobOffset(exceptionOffset, size) ? blob + exceptionOffset : 0;
        int entryBci = HotSpotMemory.getInt(blob + layout.nmethodEntryBciOffset());
        int compileLevel = HotSpotMemory.getByte(blob + layout.nmethodCompileLevelOffset());

        return new CodeCacheEntry(
                blob,
                kind,
                name,
                codeBegin,
                codeEnd,
                codeLength,
                HotSpotMemory.getInt(blob + layout.nmethodCompileIdOffset()),
                layout.compilationLevel(compileLevel),
                metadataReader.methodSignature(HotSpotMemory.getAddress(blob + layout.nmethodMethodOffset())),
                entryBci,
                entryBci != layout.invocationEntryBci(),
                nmethodState(stateValue),
                stateValue,
                exceptionStubAddress,
                exceptionStubAddress == 0 ? null : "unknown",
                handlerTableLength(blob),
                implicitExceptionTableLength(blob));
    }

    private String readBlobName(long blob) {
        long name = HotSpotMemory.getAddress(blob + layout.codeBlobNameOffset());
        return name == 0 ? null : HotSpotMemory.cString(name);
    }

    private int handlerTableLength(long blob) {
        long immutableData = HotSpotMemory.getAddress(blob + layout.nmethodImmutableDataOffset());
        int immutableDataSize = HotSpotMemory.getInt(blob + layout.nmethodImmutableDataSizeOffset());
        if (immutableData == 0 || immutableDataSize <= 0) {
            return 0;
        }
        int handlerTableOffset = HotSpotMemory.getUnsignedShort(blob + layout.nmethodHandlerTableOffset());
        int nullCheckTableOffset = HotSpotMemory.getUnsignedShort(blob + layout.nmethodNullCheckTableOffset());
        if (handlerTableOffset < 0 || nullCheckTableOffset < handlerTableOffset || nullCheckTableOffset > immutableDataSize) {
            return 0;
        }
        return nullCheckTableOffset - handlerTableOffset;
    }

    private int implicitExceptionTableLength(long blob) {
        long immutableData = HotSpotMemory.getAddress(blob + layout.nmethodImmutableDataOffset());
        int immutableDataSize = HotSpotMemory.getInt(blob + layout.nmethodImmutableDataSizeOffset());
        if (immutableData == 0 || immutableDataSize <= 0) {
            return 0;
        }
        int nullCheckTableOffset = HotSpotMemory.getUnsignedShort(blob + layout.nmethodNullCheckTableOffset());
        int scopesDataOffset = HotSpotMemory.getInt(blob + layout.nmethodScopesDataOffset());
        if (nullCheckTableOffset < 0 || scopesDataOffset < nullCheckTableOffset || scopesDataOffset > immutableDataSize) {
            return 0;
        }
        return scopesDataOffset - nullCheckTableOffset;
    }

    private static boolean validBlobOffset(int offset, int blobSize) {
        return offset > 0 && offset < blobSize;
    }

    private static NMethodState nmethodState(int value) {
        return switch (value) {
            case -1 -> NMethodState.NOT_INSTALLED;
            case 0 -> NMethodState.IN_USE;
            case 1 -> NMethodState.NOT_ENTRANT;
            default -> NMethodState.UNKNOWN;
        };
    }

    private static final class Holder {
        private static final CodeCacheScanner INSTANCE = new CodeCacheScanner();
    }
}
