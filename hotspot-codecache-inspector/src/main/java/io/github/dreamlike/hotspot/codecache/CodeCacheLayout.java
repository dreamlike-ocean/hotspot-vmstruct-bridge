package io.github.dreamlike.hotspot.codecache;

import io.github.dreamlike.hotspot.vmstruct.VmStructs;

/**
 * CodeCache 扫描需要用到的 HotSpot VMStructs 布局信息。
 *
 * <p>后缀是 {@code Offset} 的字段表示 C++ 对象内 byte offset；后缀是 {@code Address}
 * 的字段表示 HotSpot 静态字段自身的地址，读取时通常还要再解引用一次；其他 {@code int}
 * 字段是从 VMIntConstants 读取到的 HotSpot 枚举/常量值。
 *
 * @param codeCacheHeapsAddress {@code CodeCache::_heaps} 这个静态字段的地址；该字段里存的是 {@code GrowableArray<CodeHeap*>*}。
 * @param growableArrayLengthOffset {@code GrowableArrayBase::_len} 偏移，用来读取 {@code _heaps} 里有几个 {@code CodeHeap*}。
 * @param growableArrayDataOffset {@code GrowableArray<int>::_data} 偏移；复用这个模板布局读取 {@code GrowableArray<CodeHeap*>::_data}。
 * @param codeHeapMemoryOffset {@code CodeHeap::_memory} 偏移，指向保存 CodeBlob/HeapBlock 的 {@code VirtualSpace} 成员。
 * @param codeHeapSegmentMapOffset {@code CodeHeap::_segmap} 偏移，指向 segment map 的 {@code VirtualSpace} 成员。
 * @param codeHeapLog2SegmentSizeOffset {@code CodeHeap::_log2_segment_size} 偏移，用来把 segment 数量换算成字节数。
 * @param virtualSpaceLowOffset {@code VirtualSpace::_low} 偏移，表示当前已提交空间的起始地址。
 * @param virtualSpaceHighOffset {@code VirtualSpace::_high} 偏移，表示当前已提交空间的结束地址。
 * @param heapBlockSize {@code sizeof(HeapBlock)}；{@code block + heapBlockSize} 就是 {@code HeapBlock::allocated_space()}。
 * @param heapBlockHeaderOffset {@code HeapBlock::_header} 偏移。
 * @param heapBlockHeaderLengthOffset {@code HeapBlock::Header::_length} 偏移，值是这个块占用的 segment 数量。
 * @param heapBlockHeaderUsedOffset {@code HeapBlock::Header::_used} 偏移，表示这个块是否正在承载 CodeBlob。
 * @param codeBlobNameOffset {@code CodeBlob::_name} 偏移，值是普通 {@code const char*}，不是枚举。
 * @param codeBlobSizeOffset {@code CodeBlob::_size} 偏移，表示整个 CodeBlob 对象占用的字节数。
 * @param codeBlobKindOffset {@code CodeBlob::_kind} 偏移，值对应 HotSpot {@code CodeBlobKind} 枚举。
 * @param codeBlobCodeOffset {@code CodeBlob::_code_offset} 偏移，{@code blob + _code_offset} 是 {@code code_begin()}。
 * @param codeBlobDataOffset {@code CodeBlob::_data_offset} 偏移，{@code blob + _data_offset} 是 {@code code_end()/data_begin()}。
 * @param nmethodMethodOffset {@code nmethod::_method} 偏移，值是来源 Java 方法的 {@code Method*}。
 * @param nmethodEntryBciOffset {@code nmethod::_entry_bci} 偏移，普通入口是 {@code InvocationEntryBci}，OSR 入口是具体 bci。
 * @param nmethodStateOffset {@code nmethod::_state} 偏移，值是 nmethod 内部匿名状态枚举。
 * @param nmethodExceptionOffset {@code nmethod::_exception_offset} 偏移，值是 exception handler stub 相对 blob 起点的偏移。
 * @param nmethodStubOffset {@code nmethod::_stub_offset} 偏移，值是 stub 区相对 blob 起点的偏移；当前保留给后续更细分 code/stub。
 * @param nmethodHandlerTableOffset {@code nmethod::_handler_table_offset} 偏移，值是 handler table 相对 immutable data 起点的偏移。
 * @param nmethodNullCheckTableOffset {@code nmethod::_nul_chk_table_offset} 偏移，值是 implicit/null-check table 相对 immutable data 起点的偏移。
 * @param nmethodScopesDataOffset {@code nmethod::_scopes_data_offset} 偏移，当前用于计算 implicit exception table 的结束位置。
 * @param nmethodImmutableDataOffset {@code nmethod::_immutable_data} 偏移，值是 nmethod 只读元数据区域的地址。
 * @param nmethodImmutableDataSizeOffset {@code nmethod::_immutable_data_size} 偏移，值是 immutable data 总字节数。
 * @param nmethodCompileIdOffset {@code nmethod::_compile_id} 偏移，值是编译任务分配的 id。
 * @param nmethodCompileLevelOffset {@code nmethod::_comp_level} 偏移，值对应 HotSpot {@code CompLevel} 枚举。
 * @param invocationEntryBci {@code InvocationEntryBci} 常量，表示普通非 OSR nmethod 入口。
 * @param nmethodKind {@code CodeBlobKind::Nmethod} 常量值。
 * @param numberOfKinds {@code CodeBlobKind::Number_Of_Kinds} 常量值，用于过滤坏的 kind 值。
 * @param bufferKind {@code CodeBlobKind::Buffer} 常量值。
 * @param adapterKind {@code CodeBlobKind::Adapter} 常量值。
 * @param vtableKind {@code CodeBlobKind::Vtable} 常量值。
 * @param methodHandlesAdapterKind {@code CodeBlobKind::MHAdapter} 常量值。
 * @param runtimeStubKind {@code CodeBlobKind::RuntimeStub} 常量值。
 * @param deoptimizationKind {@code CodeBlobKind::Deoptimization} 常量值。
 * @param safepointKind {@code CodeBlobKind::Safepoint} 常量值。
 * @param exceptionKind {@code CodeBlobKind::Exception} 常量值；无 C2 构建可能不导出，当前用 {@code -1} 表示不可用。
 * @param uncommonTrapKind {@code CodeBlobKind::UncommonTrap} 常量值；无 C2 构建可能不导出，当前用 {@code -1} 表示不可用。
 * @param upcallKind {@code CodeBlobKind::Upcall} 常量值。
 * @param compLevelAny {@code CompLevel_any} 常量值。
 * @param compLevelNone {@code CompLevel_none} 常量值。
 * @param compLevelSimple {@code CompLevel_simple} 常量值。
 * @param compLevelLimitedProfile {@code CompLevel_limited_profile} 常量值。
 * @param compLevelFullProfile {@code CompLevel_full_profile} 常量值。
 * @param compLevelFullOptimization {@code CompLevel_full_optimization} 常量值。
 * @param compLevelCount {@code CompLevel_count} 常量值；部分 VMStructs 不导出，当前按 JDK 25 源码回退为 {@code 5}。
 */
record CodeCacheLayout(
        long codeCacheHeapsAddress,
        long growableArrayLengthOffset,
        long growableArrayDataOffset,
        long codeHeapMemoryOffset,
        long codeHeapSegmentMapOffset,
        long codeHeapLog2SegmentSizeOffset,
        long virtualSpaceLowOffset,
        long virtualSpaceHighOffset,
        long heapBlockSize,
        long heapBlockHeaderOffset,
        long heapBlockHeaderLengthOffset,
        long heapBlockHeaderUsedOffset,
        long codeBlobNameOffset,
        long codeBlobSizeOffset,
        long codeBlobKindOffset,
        long codeBlobCodeOffset,
        long codeBlobDataOffset,
        long nmethodMethodOffset,
        long nmethodEntryBciOffset,
        long nmethodStateOffset,
        long nmethodExceptionOffset,
        long nmethodStubOffset,
        long nmethodHandlerTableOffset,
        long nmethodNullCheckTableOffset,
        long nmethodScopesDataOffset,
        long nmethodImmutableDataOffset,
        long nmethodImmutableDataSizeOffset,
        long nmethodCompileIdOffset,
        long nmethodCompileLevelOffset,
        int invocationEntryBci,
        int nmethodKind,
        int numberOfKinds,
        int bufferKind,
        int adapterKind,
        int vtableKind,
        int methodHandlesAdapterKind,
        int runtimeStubKind,
        int deoptimizationKind,
        int safepointKind,
        int exceptionKind,
        int uncommonTrapKind,
        int upcallKind,
        int compLevelAny,
        int compLevelNone,
        int compLevelSimple,
        int compLevelLimitedProfile,
        int compLevelFullProfile,
        int compLevelFullOptimization,
        int compLevelCount) {

    static CodeCacheLayout load() {
        return Holder.INSTANCE;
    }

    CodeBlobKind kind(int kind) {
        if (kind == nmethodKind) {
            return CodeBlobKind.NMETHOD;
        }
        if (kind == bufferKind) {
            return CodeBlobKind.BUFFER;
        }
        if (kind == adapterKind) {
            return CodeBlobKind.ADAPTER;
        }
        if (kind == vtableKind) {
            return CodeBlobKind.VTABLE;
        }
        if (kind == methodHandlesAdapterKind) {
            return CodeBlobKind.METHOD_HANDLES_ADAPTER;
        }
        if (kind == runtimeStubKind) {
            return CodeBlobKind.RUNTIME_STUB;
        }
        if (kind == deoptimizationKind) {
            return CodeBlobKind.DEOPTIMIZATION;
        }
        if (kind == safepointKind) {
            return CodeBlobKind.SAFEPOINT;
        }
        if (kind == exceptionKind) {
            return CodeBlobKind.EXCEPTION;
        }
        if (kind == uncommonTrapKind) {
            return CodeBlobKind.UNCOMMON_TRAP;
        }
        if (kind == upcallKind) {
            return CodeBlobKind.UPCALL;
        }
        return CodeBlobKind.UNKNOWN;
    }

    CompilationLevel compilationLevel(int level) {
        if (level == compLevelAny) {
            return CompilationLevel.ANY;
        }
        if (level == compLevelNone) {
            return CompilationLevel.NONE;
        }
        if (level == compLevelSimple) {
            return CompilationLevel.SIMPLE;
        }
        if (level == compLevelLimitedProfile) {
            return CompilationLevel.LIMITED_PROFILE;
        }
        if (level == compLevelFullProfile) {
            return CompilationLevel.FULL_PROFILE;
        }
        if (level == compLevelFullOptimization) {
            return CompilationLevel.FULL_OPTIMIZATION;
        }
        if (level == compLevelCount) {
            return CompilationLevel.COUNT;
        }
        return CompilationLevel.UNKNOWN;
    }

    boolean isKnownKind(int kind) {
        return 0 < kind && kind < numberOfKinds;
    }

    private static CodeCacheLayout create() {
        VmStructs vm = VmStructs.current();
        return new CodeCacheLayout(
                vm.staticAddress("CodeCache", "_heaps"),
                vm.offset("GrowableArrayBase", "_len"),
                vm.offset("GrowableArray<int>", "_data"),
                vm.offset("CodeHeap", "_memory"),
                vm.offset("CodeHeap", "_segmap"),
                vm.offset("CodeHeap", "_log2_segment_size"),
                vm.offset("VirtualSpace", "_low"),
                vm.offset("VirtualSpace", "_high"),
                vm.typeSize("HeapBlock"),
                vm.offset("HeapBlock", "_header"),
                vm.offset("HeapBlock::Header", "_length"),
                vm.offset("HeapBlock::Header", "_used"),
                vm.offset("CodeBlob", "_name"),
                vm.offset("CodeBlob", "_size"),
                vm.offset("CodeBlob", "_kind"),
                vm.offset("CodeBlob", "_code_offset"),
                vm.offset("CodeBlob", "_data_offset"),
                vm.offset("nmethod", "_method"),
                vm.offset("nmethod", "_entry_bci"),
                vm.offset("nmethod", "_state"),
                vm.offset("nmethod", "_exception_offset"),
                vm.offset("nmethod", "_stub_offset"),
                vm.offset("nmethod", "_handler_table_offset"),
                vm.offset("nmethod", "_nul_chk_table_offset"),
                vm.offset("nmethod", "_scopes_data_offset"),
                vm.offset("nmethod", "_immutable_data"),
                vm.offset("nmethod", "_immutable_data_size"),
                vm.offset("nmethod", "_compile_id"),
                vm.offset("nmethod", "_comp_level"),
                vm.intConstant("InvocationEntryBci"),
                vm.intConstant("CodeBlobKind::Nmethod"),
                vm.intConstant("CodeBlobKind::Number_Of_Kinds"),
                vm.intConstant("CodeBlobKind::Buffer"),
                vm.intConstant("CodeBlobKind::Adapter"),
                vm.intConstant("CodeBlobKind::Vtable"),
                vm.intConstant("CodeBlobKind::MHAdapter"),
                vm.intConstant("CodeBlobKind::RuntimeStub"),
                vm.intConstant("CodeBlobKind::Deoptimization"),
                vm.intConstant("CodeBlobKind::Safepoint"),
                vm.optionalIntConstant("CodeBlobKind::Exception").orElse(-1),
                vm.optionalIntConstant("CodeBlobKind::UncommonTrap").orElse(-1),
                vm.intConstant("CodeBlobKind::Upcall"),
                vm.intConstant("CompLevel_any"),
                vm.intConstant("CompLevel_none"),
                vm.intConstant("CompLevel_simple"),
                vm.intConstant("CompLevel_limited_profile"),
                vm.intConstant("CompLevel_full_profile"),
                vm.intConstant("CompLevel_full_optimization"),
                vm.optionalIntConstant("CompLevel_count").orElse(5));
    }

    private static final class Holder {
        private static final CodeCacheLayout INSTANCE = create();
    }
}
