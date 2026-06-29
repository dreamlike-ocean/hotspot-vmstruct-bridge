# VMStructs 可扩展能力

本文记录当前 JDK 25 HotSpot 通过 VMStructs 暴露出来、可以在本项目后续支持的能力。当前项目已经使用 `gHotSpotVMStructs` 读取字段偏移和静态字段地址；还能继续扩展读取 `gHotSpotVMTypes`、`gHotSpotVMIntConstants`、`gHotSpotVMLongConstants`。这四张表本身都是 `libjvm` 动态导出的入口，适合做一个进程内的简化版 SA type database。

本地符号调研显示，很多好玩的 HotSpot C++ 函数不在动态符号表里，普通 `dlsym` 拿不到；但 Linux/GraalVM JDK 25 和本地 OpenJDK build 的 `.symtab` 里能找到这些 local/hidden 符号。当前项目的 ELF/Mach-O 文件符号解析路线可以继续沿用，但业务层不应该直接暴露 mangled name。

本清单刻意不以稳定性或安全性为边界，只记录技术上可以尝试的方向。

## 1. 全 JVM class graph 枚举

从 `ClassLoaderDataGraph::_head` 进入 `ClassLoaderData` 链表，再沿 `ClassLoaderData::_klasses` 枚举所有已加载类。这个路径可以看到普通反射 API 不容易覆盖的 hidden class、generated class 和不同 class loader 下的 Klass。

可继续读取 `InstanceKlass::_methods`、`_constants`、`_annotations`、`_local_interfaces`、`_transitive_interfaces`，做一个进程内 class browser。

SA 的 `SystemDictionaryHelper`、`ClassWriter` 和 `jcore` 路径可以作为实现参考：它们基于 VMStructs 读 `InstanceKlass`、`ConstantPool` 和方法数组，再把运行中 VM 的类信息还原为可浏览结构。

## 2. 对象解剖器

从任意 Java 对象拿到 oop 地址后，读取 `oopDesc::_mark` 和 `oopDesc::_compressed_klass`。结合 `CompressedKlassPointers::_base/_shift` 解出 Klass 地址，再读 `Klass::_name` 和 `Symbol::_body` 得到 JVM 内部类名。

mark word 可以通过 `markWord::*` long constants 解出 hash、age、lock state、monitor state。

JOL 的对象地址读取是诊断级做法：把对象放进 `Object[1]`，用 `Unsafe` 读引用 slot，再按 compressed oop base/shift 解码。它没有 pin 对象，也没有解决 Java 层多步读取与移动 GC 之间的原子性问题。本项目如果继续坚持不走 `Unsafe`，更适合把对象解剖定位为 best-effort demo，或者只在 `-XX:+UseEpsilonGC` 这类不移动/不回收实验环境下展示。

## 3. 对象变形和类型混淆

直接改对象头中的 `_compressed_klass`，可以把一个对象伪装成另一个 Klass。这个方向能测试 HotSpot 对对象布局、JIT 类型假设、GC 扫描和 verifier 之间的边界。

风险很高：字段布局、oop map、GC barrier 和 C2 type profile 很容易被破坏。

## 4. Method 元数据浏览和篡改

`Method::_constMethod`、`_method_data`、`_method_counters`、`_code`、`_from_compiled_entry`、`_from_interpreted_entry`、`_i2i_entry` 都已经暴露。

可以展示 Method 地址、方法名、签名、max stack、max locals、参数槽数、当前 nmethod、compiled/interpreted entry。也可以直接改 entry，把一个 Java 方法转接到 raw code、另一个 Method 的 compiled entry 或解释器入口。

当前项目已经走通 `jmethodID -> Method*`、`WhiteBox::compile_method`、`BufferBlob::create` 和 Method entry 改写。`Method::_flags` 没有直接导出，但可以继续按 JDK 25 布局从 `_intrinsic_id` 前推，或者后续用更完整的 VMTypes/源码校验来集中管理。

## 5. 字节码 patch

通过 `Method::_constMethod` 找到 `ConstMethod`，再根据 `ConstMethod::_code_size` 和 `ConstMethod` 内部布局定位 bytecode 区域，运行期修改方法字节码。

解释执行路径会更容易观察到效果；已经生成的 nmethod 需要配合清理 `_code`、触发 deopt 或让调用方重新解析。

## 6. vtable / itable patch

`vtableEntry::_method` 已暴露。沿 `Klass` 的 vtable 区域可以改某个虚方法分发表项，让 virtual call 指向另一个 Method。

这个方向比改单个 Method entry 更接近动态派发层，能影响同类对象的虚调用行为。

## 7. ConstantPool / ConstantPoolCache patch

`ConstantPool::_tags`、`_cache`、`_resolved_klasses` 以及 `ConstantPoolCache::_resolved_field_entries`、`_resolved_method_entries`、`_resolved_indy_entries` 已暴露。

可以改已解析字段、方法、类和 invokedynamic 目标，用来实验 lambda、MethodHandle、indy bootstrap 和解析缓存行为。

这个方向适合做一个“解析缓存反查器”：从 `Method::_constMethod -> ConstMethod::_constants -> ConstantPool::_cache` 进入，打印某个 bytecode 的 HotSpot 内部 constant pool index、resolved entry 和当前目标 Method/Klass。SA 的 `BytecodeWithCPIndex` 和 `ClassWriter` 对 ConstantPoolCache index 到 ConstantPool index 的转换有现成参考。

## 8. nmethod / CodeBlob 浏览和机器码热 patch

`CodeBlob::_kind`、`_name`、`_size`、`_code_offset`、`_data_offset`、`_oop_maps`，以及 `nmethod::_entry_offset`、`_verified_entry_offset`、`_compile_id`、`_comp_level`、`_state` 等字段已暴露。

可以定位 JIT code begin、verified entry、stub、exception handler、deopt handler，并直接 patch 机器码。这个能力可用于把 callsite 替换成 `nop`、`prefetch`、短跳转或自定义探针。

CodeCache 浏览可以走两条路：只用 VMStructs 读 `CodeCache::_heaps`、`CodeHeap::_memory/_segmap/_log2_segment_size`，按 SA `CodeHeap.iterate` 的方式扫描；或者定位 `.symtab` 中的 `CodeCache::blobs_do`、`CodeCache::nmethods_do`、`CodeCache::find_blob`。后者涉及 C++ callback/closure ABI，纯 FFM 调起来不如手写扫描简单。

## 9. JIT profile 操控

`MethodData`、`MethodCounters`、`InvocationCounter` 相关字段已暴露。可以读取或修改 invocation counter、backedge counter、throwout counter 和 profile data。

这个方向可以诱导编译、影响 inline 决策、改变 type profile，也可以制造错误的 speculative optimization 输入。

## 10. deopt / debug info 解析和篡改

`PcDesc`、`ImmutableOopMapSet`、`ImmutableOopMapPair`、`ImmutableOopMap`、`OopMapValue`、`CompressedStream` 已暴露。

可以从 nmethod 的 PC 反查 Java scope、bci、locals、oop map。反向修改这些结构可以测试 deoptimizer 和 GC 对 compiled frame metadata 的依赖。

## 11. 线程列表和 JavaThread 操控

`ThreadsSMRSupport::_java_thread_list`、`ThreadsList::_threads/_length`、`JavaThread::_threadObj`、`_vthread`、`_thread_state`、`_stack_base`、`_stack_size`、`_exception_oop`、`_suspend_flags` 等字段已暴露。

可以做进程内线程 dump、虚拟线程 carrier 映射、栈范围读取、pending exception 观察。写这些字段可以测试 thread state protocol 和异常投递路径。

虚拟线程建议分成多个视角展示：`ThreadsList` 只能看到 carrier/platform `JavaThread` 和 mounted virtual thread；`ThreadContainers` 在 `jdk.trackAllThreads=false` 时根容器是 `CountingRootContainer`，只维护计数，不维护虚拟线程集合；`jcmd Thread.dump_to_file` 的普通实现走 `jdk.internal.vm.ThreadDumper` 和 `ThreadSnapshot`，全量 unmounted virtual thread 只有 heap dump 代码通过 heap walk 找 `java.lang.VirtualThread` 对象。换句话说，不 heap walk 就不要承诺“枚举全部虚拟线程”。

## 12. TLAB 手工分配对象

`Thread::_tlab` 和 `ThreadLocalAllocBuffer::_start/_top/_end/_desired_size` 已暴露。

可以在当前线程 TLAB 内自己 bump pointer，写 mark word、klass pointer 和字段内容，伪造一个 Java 对象。后续需要配合对象布局、对齐、compressed oops 和 GC barrier。

## 13. 锁和 monitor 操控

`ObjectMonitor::_object`、`_owner`、`_contentions`、`_waiters`、`_recursions`，以及 `ObjectSynchronizer::_in_use_list` 已暴露。

可以枚举 inflated monitors、观察锁竞争、定位当前 owner。写这些字段可以测试 monitor enter/exit、wait/notify 和 deflation 的边界。

SA 的 `ObjectSynchronizer.objectMonitorIterator` 就是从 `ObjectSynchronizer::_in_use_list` 读 `MonitorList::_head`，再沿 `ObjectMonitor::_next_om` 走链表。`ObjectMonitor::ANONYMOUS_OWNER`、`NO_OWNER`、`DEFLATER_MARKER` 在 long constants 表里，应该通过常量表读取而不是硬编码。

## 14. GC 内部视图和干预

shared GC 表暴露了 `Universe::_collectedHeap`、`CollectedHeap::_reserved`、`_total_collections`、`BarrierSet::_barrier_set`、`CardTable::_byte_map`、`CardTableBarrierSet::_card_table` 等字段。

具体 GC 还会导出自己的结构：G1 暴露 region table 和 monitoring support；ZGC 暴露 page table、colored pointer masks、forwarding table；Shenandoah 暴露 region state、free set 和 committed size。

`Universe::_collectedHeap` 只能帮你找到当前 heap，不是 GC 操作入口。`G1CollectedHeap::pin_object/unpin_object`、`ZCollectedHeap::pin_object/unpin_object`、`CollectedHeap::collect_as_vm_thread`、`GCLocker::*` 等符号在本地 `.symtab` 可定位，但不动态导出。pin/unpin 还要求先有合法 `oop` 和 `JavaThread*`，所以不能解决“为了拿 oop 才想 pin”的闭环问题。

## 15. JVM flags 活体读写

`JVMFlag::flags`、`numFlags`、`JVMFlag::_type`、`_name`、`_addr`、`_flags` 已暴露。

可以枚举所有 `-XX` flag、读取 origin、读取或修改 flag 背后的真实值。部分 flag 在运行期修改会马上影响行为，部分会制造启动期状态和运行期状态不一致。

## 16. PerfMemory / jvmstat 读写

`PerfMemory::_start/_end/_top/_capacity/_prologue`，以及 `PerfDataPrologue`、`PerfDataEntry` 字段已暴露。

可以在进程内解析 jstat 使用的 perf counters，也可以写 counter 来伪造外部监控看到的数据。

## 17. JNI handle / OopHandle 操作

`JNIHandles::_global_handles`、`_weak_global_handles`、`JNIHandleBlock::_handles/_top/_next`，以及 `OopHandle::_obj` 已暴露。

可以扫描全局 JNI 引用、弱引用和 VM 内部 oop handle。写 handle 可以把某个 native/VM handle 指向另一个 oop。

`OopHandle::_obj` 是一个 `oop*` slot，长期持有 slot 可以，不能长期持有从 slot resolve 出来的 raw oop。`JNIHandles::resolve_external_guard`、`JNIHandles::resolve_impl<0,false>`、`OopHandle` 构造函数等 C++ 符号在 `.symtab` 中可定位但不是 dynamic export。纯 Java/FFM 多步读取 `slot -> oop -> header` 依然不是原子的；如果要做强一致版本，需要把 resolve、pin 或 header 读取收进同一个 VM/native helper。

## 18. JVMTI 能力位篡改

`JvmtiExport` 部分静态能力位通过 JVMTI struct 暴露，例如 `_can_access_local_variables`、`_can_hotswap_or_post_breakpoint`、`_can_post_on_exceptions`、`_can_walk_any_space`。

可以实验打开或伪造 JVMTI capability 状态，但后端不一定完成了对应能力的初始化。

JDK 25 里还有 virtual thread 相关 JVMTI 能力和入口，例如 suspend/resume all virtual threads、mount/unmount/start/end event。能力位篡改最多能影响可见状态，不等于后端事件、线程状态和 JVMTI env 已经完成初始化，适合作为观测实验，不适合作为功能开关。

## 建议落地顺序

1. 扩展 `VmStructs` 为通用 type database，支持 field/type/int constant/long constant，并把 `VmStructs`、常见 layout、constant reader 都做成缓存单例。
2. 增加只读 inspector：object header、klass、method、nmethod、code blob、object monitor、JavaThread。
3. 增加 class graph、CodeCache browser 和多视角 virtual thread browser，明确区分 mounted、container-tracked、count-only、heap-walk 才能看到的虚拟线程。
4. 增加可选的 patch API：Method entry、ConstantPoolCache、vtable、nmethod code、JIT profile counter。
5. 最后再碰 TLAB 手工造对象、GC/monitor/thread state 写入，以及需要 C++ closure/VMOperation 对象的内部符号调用。
