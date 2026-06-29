# hotspot-vmstruct-bridge

JDK 25 HotSpot-family VMStructs / native runtime bridge PoC。

这里的 HotSpot 指 VM runtime 家族：OpenJDK/Oracle/Corretto/GraalVM JDK 这类仍然使用 HotSpot `libjvm` 的 JDK；不包括 OpenJ9，也不包括 GraalVM Native Image 产物。

当前是 Maven 多模块项目：

```text
hotspot-vmstruct-base     基础 VMStructs、libjvm lookup、裸地址读写、平台符号解析和 layout
hotspot-codecache-inspector 纯 Java/FFM CodeCache 观察器
hotspot-method-bridge     Method* -> nmethod / CodeCache entry link PoC
```

`hotspot-method-bridge` 的目标很窄：

1. 不依赖 `-Xcomp` / `-Xbatch`。
2. 不做 warmup loop。
3. 不使用 WhiteBox Java API，也不使用 JVMCI。
4. 业务实现不依赖 `Unsafe`。
5. 用 FFM 读取当前进程 `libjvm`，把 target 链接到同签名 carrier 的合法 `nmethod`。
6. aarch64 上可以把一段 raw `byte[]` 机器码安装进 HotSpot `CodeCache`，再让 target 跳到这段入口。

## 运行

```bash
./run.sh
```

默认 JDK：

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current
```

换 JDK：

```bash
JAVA_HOME=/path/to/jdk-25 ./run.sh
```

成功时 `./run.sh` 以 exit code 0 结束；脚本内部使用 `mvn -q test`，正常情况下不会打印 Maven 成功横幅。

JUnit 断言覆盖：link 前没有业务 warmup；解释器调用 `target` 后只增加 `carrierCount`；已编译 caller 循环调用后仍只增加 `carrierCount`；aarch64 raw 机器码进入 `CodeCache` 后能返回入参 `Object` 的 oop 地址。

## 实现

核心类：

```text
hotspot-method-bridge/src/main/java/io/github/dreamlike/hotspot/methodbridge/HotSpotMethodBridge.java
hotspot-vmstruct-base/src/main/java/io/github/dreamlike/hotspot/vmstruct/VmStructs.java
hotspot-vmstruct-base/src/main/java/io/github/dreamlike/hotspot/vmstruct/HotSpotLibrary.java
hotspot-vmstruct-base/src/main/java/io/github/dreamlike/hotspot/vmstruct/HotSpotMemory.java
```

JUnit 验证入口在：

```text
hotspot-method-bridge/src/test/java/io/github/dreamlike/hotspot/methodbridge/HotSpotMethodBridgeTest.java
```

关键步骤：

1. 通过 `gHotSpotVMStructs` 找到 HotSpot 字段偏移；`VMStructEntry` 表项布局也从 HotSpot 导出的 `gHotSpotVMStructEntry*Offset` 和 `gHotSpotVMStructEntryArrayStride` 读取。
2. 运行时用 JNI `Class.forName(name, false, Thread.currentThread().getContextClassLoader())` 找到已有的 `JniMethodAddressBridge`，并通过 `RegisterNatives` 把它的 `resolve(Method): long` 绑定到 FFM upcall。
3. native upcall 里拿到 `Method` 的 `jobject`，调用 JNI `FromReflectedMethod` 得到 `jmethodID`，再调用 HotSpot `Method::checked_resolve_jmethod_id` 得到 `Method*`。
4. 按当前平台解析 `libjvm` local symbol，调用 `Thread::current()` 取当前 HotSpot 线程，再调用 `WhiteBox::compile_method(Method*, int, int, JavaThread*)` 让 carrier 生成合法 nmethod。
5. 给 target 的 `MethodFlags` 打 not-compilable 和 dont-inline 标记，避免 target 之后被 JVM 自己编译覆盖，也避免编译调用方把 target 的 Java bytecode inline 进去。
6. 按 `Method::set_code` 的发布顺序写 target：

```text
target._code = carrier._code
storeFence
target._from_compiled_entry = carrier._from_compiled_entry
storeFence
target._from_interpreted_entry = carrier._from_interpreted_entry
```

所有 native 地址读写都走 FFM `MemorySegment`，没有 `Unsafe`、slot 扫描或 oop 地址解码。

raw `byte[]` 机器码路径：

1. 调 HotSpot `BufferBlob::create(const char*, uint)` 分配一块 `BufferBlob`。
2. 通过 `gHotSpotVMStructs` 读取 `CodeBlob::_code_offset`，`entry = blob + _code_offset`。
3. macOS/aarch64 不能在 FFM downcall stub 里直接切 `WXWrite`，所以 `MachO.installCodeBlob` 进入普通 `mmap + mprotect(RX)` trampoline；trampoline 内部切 `WXWrite`、调用 `BufferBlob::create`、拷贝机器码、flush icache、切回 `WXExec`，最后才返回 Java/FFM。
4. Linux 不需要 W^X 切换，`Elf.installCodeBlob` 直接 FFM 调 `BufferBlob::create`，用 `MemorySegment.copyFrom` 写入 CodeCache，再按符号探测选择 icache flush：优先 `__clear_cache`，其次 `AbstractICache::invalidate_range`，都没有就 no-op。
5. raw 路径不写 `target._code`，因为 `BufferBlob` 不是 `nmethod`；只写 `target._from_compiled_entry = entry` 和 `target._from_interpreted_entry = target.get_i2c_entry()`。

## JDK 25 源码对应

源码链接固定到 OpenJDK 远端 GitHub blame：`openjdk/jdk@548a95379f159a0dc369f6bb80d8167ec835c7cd`。

### VMStructEntry

[`src/hotspot/share/runtime/vmStructs.hpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/runtime/vmStructs.hpp#L67-L77)：

```cpp
typedef struct {
  const char* typeName;
  const char* fieldName;
  const char* typeString;
  int32_t  isStatic;
  uint64_t offset;
  void* address;
} VMStructEntry;
```

[`src/hotspot/share/runtime/vmStructs.cpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/runtime/vmStructs.cpp#L1961-L1968)：

```cpp
JNIEXPORT VMStructEntry* gHotSpotVMStructs = VMStructs::localHotSpotVMStructs;
JNIEXPORT uint64_t gHotSpotVMStructEntryTypeNameOffset = offset_of(VMStructEntry, typeName);
JNIEXPORT uint64_t gHotSpotVMStructEntryFieldNameOffset = offset_of(VMStructEntry, fieldName);
JNIEXPORT uint64_t gHotSpotVMStructEntryTypeStringOffset = offset_of(VMStructEntry, typeString);
JNIEXPORT uint64_t gHotSpotVMStructEntryIsStaticOffset = offset_of(VMStructEntry, isStatic);
JNIEXPORT uint64_t gHotSpotVMStructEntryOffsetOffset = offset_of(VMStructEntry, offset);
JNIEXPORT uint64_t gHotSpotVMStructEntryAddressOffset = offset_of(VMStructEntry, address);
JNIEXPORT uint64_t gHotSpotVMStructEntryArrayStride = STRIDE(gHotSpotVMStructs);
```

含义：

- `gHotSpotVMStructs`：运行时表地址，受动态库加载地址影响，不能固定。
- `gHotSpotVMStructEntry*Offset`：`VMStructEntry` 自身字段的 byte offset，用来解析 VMStructs 表。
- `gHotSpotVMStructEntryArrayStride`：下一条 `VMStructEntry` 的跨度。
- `typeName`：字段所属 HotSpot 类型名，例如 `Method`。
- `fieldName`：字段名，例如 `_code`。
- `isStatic`：`0` 表示 `offset` 是对象内偏移；`1` 表示 `address` 是静态字段地址。
- `offset`：非静态字段在 C++ 对象里的 byte offset，本项目只查这个。
- `address`：静态字段真实地址，本项目当前不用。

### Method 字段

[`src/hotspot/share/oops/method.hpp` 字段定义](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/oops/method.hpp#L91-L102)，[`offset helper`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/oops/method.hpp#L615-L626)：

```cpp
address _i2i_entry;
volatile address _from_compiled_entry;
nmethod* volatile _code;
volatile address _from_interpreted_entry;

static ByteSize from_compiled_offset()     { return byte_offset_of(Method, _from_compiled_entry); }
static ByteSize code_offset()              { return byte_offset_of(Method, _code); }
static ByteSize from_interpreted_offset()  { return byte_offset_of(Method, _from_interpreted_entry); }
static ByteSize interpreter_entry_offset() { return byte_offset_of(Method, _i2i_entry); }
```

含义：

- `_code`：该 Java 方法当前绑定的 `nmethod*`，没有编译产物时为 `nullptr`。
- `_from_compiled_entry`：已编译调用者进入该方法时跳转的入口。
- `_i2i_entry`：该方法自己的解释器入口，`link_method` 初始化时和 `_from_interpreted_entry` 一起设为 interpreter entry。
- `_from_interpreted_entry`：解释器调用该方法时跳转的可变缓存入口；有 compiled code 时通常走 i2c 适配器。
- `Method::_code/_from_compiled_entry/_from_interpreted_entry/_i2i_entry` 的偏移不固定，需要查偏移时仍从 `gHotSpotVMStructs` 查。

### Method* 解析

[`src/hotspot/share/prims/jni.cpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/prims/jni.cpp#L362-L380)：

```cpp
oop reflected = JNIHandles::resolve_non_null(method);
mirror = java_lang_reflect_Method::clazz(reflected);
slot   = java_lang_reflect_Method::slot(reflected);
Klass* k1 = java_lang_Class::as_Klass(mirror);
k1->initialize(CHECK_NULL);
Method* m = InstanceKlass::cast(k1)->method_with_idnum(slot);
ret = m == nullptr ? nullptr : m->jmethod_id();
```

[`src/hotspot/share/oops/method.cpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/oops/method.cpp#L2144-L2155)：

```cpp
Method* Method::checked_resolve_jmethod_id(jmethodID mid) {
  if (mid == nullptr) return nullptr;
  Method* o = resolve_jmethod_id(mid);
  if (o == nullptr) return nullptr;
  return o->method_holder()->is_loader_alive() ? o : nullptr;
}
```

PoC 不直接读 Java `Method.slot`。Java 侧通过已加载的 `JniMethodAddressBridge.resolve(Method)` 让 JVM 把 `Method` 当 `jobject` 传给 upcall；upcall 先调 JNI `FromReflectedMethod` 得到 `jmethodID`，再调 `Method::checked_resolve_jmethod_id` 得到 `Method*`。

### nmethod 发布顺序

[`src/hotspot/share/oops/method.cpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/oops/method.cpp#L1371-L1410)：

```cpp
mh->_code = code;
OrderAccess::storestore();
mh->_from_compiled_entry = code->verified_entry_point();
OrderAccess::storestore();
mh->_from_interpreted_entry = mh->get_i2c_entry();
```

`HotSpotMethodBridge.linkToCarrier` 复制 carrier 的三个字段到 target 时保持同样顺序：

```text
target._code = carrier._code
storeFence
target._from_compiled_entry = carrier._from_compiled_entry
storeFence
target._from_interpreted_entry = carrier._from_interpreted_entry
```

这里故意不复制 `_i2i_entry`。HotSpot 的普通 `Method::set_code` 分支只把 `_from_interpreted_entry` 改成 `get_i2c_entry()`，不会改 `_i2i_entry`；`clear_code` 才把 `_from_interpreted_entry` 还原成 `_i2i_entry`。

解释器普通调用也读 `_from_interpreted_entry`，例如 [`javaCalls.cpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/runtime/javaCalls.cpp#L384-L392) 和模板解释器的 [`jump_from_interpreted`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/cpu/aarch64/interp_masm_aarch64.cpp#L359-L377)。只有 JVMTI interpreter-only / single-step 这类路径会退回 `_i2i_entry`。本 PoC 的目标是让普通 interpreted/compiled 调用进入 carrier 的 compiled code，不覆盖 target 自己的纯解释器入口。

### Raw 机器码和 CodeCache

[`src/hotspot/share/code/codeBlob.hpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/code/codeBlob.hpp#L119-L124)：

```cpp
int _content_offset;
int _code_offset;
int _data_offset;
```

[`CodeBlob::code_begin`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/code/codeBlob.hpp#L240-L245)：

```cpp
address header_begin() const { return (address) this; }
address code_begin() const   { return (address) header_begin() + _code_offset; }
```

[`VMStructs` 导出 `CodeBlob::_code_offset`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/runtime/vmStructs.cpp#L489-L498)，所以 Java 侧不硬编码 `CodeBlob` 头大小，而是查：

```text
entry = BufferBlob* + offset(CodeBlob::_code_offset)
```

[`BufferBlob::create`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/code/codeBlob.cpp#L402-L418) 会在 HotSpot `CodeCache` 里分配 `BufferBlob`：

```cpp
BufferBlob* BufferBlob::create(const char* name, uint buffer_size) {
  ThreadInVMfromUnknown __tiv;
  ...
  blob = new (size) BufferBlob(name, CodeBlobKind::Buffer, size);
  ...
  return blob;
}
```

raw `byte[]` 链接时不能把 `BufferBlob*` 写进 `Method::_code`。`_code` 的类型是 `nmethod* volatile`，HotSpot 的 deopt、class unloading、`method->code()` 路径会按 `nmethod` 语义解释它。这里的 raw code 只是一个 `BufferBlob` 里的 entry，所以只写：

```text
target._code = nullptr
target._from_compiled_entry = blob->code_begin()
target._from_interpreted_entry = target.get_i2c_entry()
```

解释器进入 target 时先走 i2c adapter。以 aarch64 为例，[`gen_i2c_adapter`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/cpu/aarch64/sharedRuntime_aarch64.cpp#L562-L657) 会把解释器栈参数搬到 compiled Java ABI 寄存器，最后跳到 `Method::_from_compiled_entry`。因此 raw entry 需要遵守 HotSpot compiled Java ABI，不是 JNI ABI。

`hotspot-codecache-inspector` 是只读观察模块，不引入 JNI/native 源码。入口：

```java
CodeCacheEntry[] entries = HotSpotCodeCache.snapshot();
CodeCacheEntry[] nmethods = HotSpotCodeCache.nmethods();
```

第一版按 SA 的 `CodeHeap` 扫描方式遍历当前进程的 `CodeCache::_heaps`，输出每个可识别 `CodeBlob` 的 kind、name、code 地址/长度；其中 kind 映射成 `CodeBlobKind` enum，name 仍然是 HotSpot 的 `const char*`。对 `nmethod` 额外输出 compile id、`CompilationLevel`、`Method` 来源签名、entry bci、OSR 标记、`NMethodState`、exception handler stub 地址、handler table 长度和 implicit exception table 长度。

这个遍历不进入 VM，不拿 `CodeCache_lock`，因此不会主动制造 safepoint；相应地它也不阻止并发编译、CodeCache GC 或 nmethod 状态变化，结果是 best-effort snapshot。`nmethod::_exception_offset` 只能定位通用 exception handler stub，不能直接给出 Java catch 类型，所以 `exceptionType` 第一版只在存在 stub 时标成 `unknown`。

macOS/aarch64 的 W^X 定义在 [`os.hpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/runtime/os.hpp#L143-L146)：

```cpp
enum WXMode {
  WXWrite = 0,
  WXExec = 1,
};
```

[`os::current_thread_enable_wx`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/os_cpu/bsd_aarch64/os_bsd_aarch64.cpp#L516-L525) 本质上切当前线程对 `MAP_JIT` 区域的写/执行权限：

```cpp
void os::current_thread_enable_wx(WXMode mode) {
  bool exec_enabled = mode != WXWrite;
  if (exec_enabled != _jit_exec_enabled) {
    pthread_jit_write_protect_np(exec_enabled);
    _jit_exec_enabled = exec_enabled;
  }
}
```

所以 macOS raw 安装必须在普通 RX trampoline 中一次性完成：

```text
FFM downcall stub(CodeCache, WXExec)
  -> trampoline(普通 mmap RX)
     -> WXWrite
     -> BufferBlob::create + copy byte[] + flush icache
     -> WXExec
  -> return FFM downcall stub(CodeCache, WXExec)
```

如果在 FFM stub 里切到 `WXWrite` 后直接返回 Java，当前线程会立刻尝试执行 CodeCache 里的 FFM stub，结果就是 crash。

icache flush 不依赖 HotSpot C++ inline 符号。HotSpot 自己在 macOS/aarch64 上用 [`__clear_cache`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/os_cpu/bsd_aarch64/icache_bsd_aarch64.hpp#L40-L42)，Linux/aarch64 常规路径也走 [`__builtin___clear_cache`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/os_cpu/linux_aarch64/icache_linux_aarch64.hpp#L44-L60)。本项目在 Mach-O trampoline 内直接执行 `dc cvau / ic ivau / dsb / isb`，并固定按 64B cache line 过量 flush，避免 macOS 用户态读取 `CTR_EL0` 触发 `SIGILL`；ELF 路径不走 trampoline，先探测 `__clear_cache`，没有再探测 `AbstractICache::invalidate_range`，最后 no-op。

注意 `AbstractICache::invalidate_range` 不是 header-only，确实有 out-of-line 实现。但 Linux/aarch64 的 `ICache` 静态隐藏了这个 base 实现，HotSpot 编译期调用的是 `ICache::invalidate_range` inline 版本；AArch64 的 `ICacheStubGenerator` 还会把 `_flush_icache_stub` 设为 `nullptr`，所以 ELF 路径优先探测 `__clear_cache`。

### 强制编译入口

[`src/hotspot/share/prims/whitebox.hpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/prims/whitebox.hpp#L70)：

```cpp
static bool compile_method(Method* method, int comp_level, int bci, JavaThread* THREAD);
```

[`src/hotspot/share/prims/whitebox.cpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/prims/whitebox.cpp#L1103-L1129)：

```cpp
nmethod* nm = CompileBroker::compile_method(
    mh, bci, comp_level, mh->invocation_count(),
    CompileTask::Reason_Whitebox, CHECK_false);
```

PoC 直接链接这个 C++ 符号，不启用 Java WhiteBox API；`bci=-1` 表示普通 invocation entry，不是 OSR。

### 禁止 target 编译和内联

target 如果被 JVM 正常编译，C1/C2 依据的是 target 自己的 Java bytecode 和 `Method` 元数据，不会依据已经写进去的 carrier nmethod。安装新代码时 HotSpot 会执行 [`method->set_code(method, nm)`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/ci/ciEnv.cpp#L1102-L1106)，直接覆盖 target 的 `_code/_from_compiled_entry/_from_interpreted_entry`；安装前还会把旧 `method->code()` 做 [`make_not_used`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/ci/ciEnv.cpp#L1081-L1092)，旧 code 如果是 carrier nmethod，就可能把 carrier 一起打废。

所以 `HotSpotMethodBridge.linkToCarrier` 不依赖外部 compiler directives，而是直接给 target 的 `Method::_flags` 原子 OR 下面这些 JDK 25 `MethodFlags` 位：

[`src/hotspot/share/oops/methodFlags.hpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/oops/methodFlags.hpp#L52-L56)：

```cpp
status(is_not_c2_compilable        , 1 << 8)
status(is_not_c1_compilable        , 1 << 9)
status(is_not_c2_osr_compilable    , 1 << 10)
status(dont_inline                 , 1 << 12)
```

`Method::_flags` 在 [`method.hpp`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/oops/method.hpp#L79-L82) 里位于 `_access_flags` 后、`_intrinsic_id` 前。`gHotSpotVMStructs` 没暴露 `Method::_flags` 本身，所以本项目在 JDK 25 上用：

```text
methodFlagsOffset = offset(Method::_intrinsic_id) - sizeof(MethodFlags::_status)
```

`is_not_c1_compilable/is_not_c2_compilable` 会被 [`Method::is_not_compilable`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/oops/method.cpp#L1122-L1133) 和 [`ciMethod` 初始化](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/ci/ciMethod.cpp#L91-L92) 消费；`dont_inline` 会被 C2 的 [`InlineTree::should_not_inline`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/opto/bytecodeInfo.cpp#L196-L212) 和 C1 的 [`GraphBuilder::should_not_inline`](https://github.com/openjdk/jdk/blame/548a95379f159a0dc369f6bb80d8167ec835c7cd/src/hotspot/share/c1/c1_GraphBuilder.cpp#L3559-L3563) 消费。

这不是给 `nmethod` 打标。inline 决策发生在编译 caller 时，编译器看的是 callee 的 `Method/ciMethod` 元数据；carrier 的 nmethod 上没有一个可用标记能阻止 caller inline target 的 Java bytecode。target 级 `dont_inline` 只影响这个 target 作为 callee 时的内联，不会全局禁止其他方法内联。

注意：`WhiteBox::compile_method(target, level)` 在 target 已经被手工写入 carrier code 后，可能因为 `mh->code() != nullptr` 返回 `true`，这不代表生成并安装了新的 target nmethod。测试因此只断言 target 的 `_code/_from_compiled_entry/_from_interpreted_entry` 没被覆盖。

## 边界

`Method::_code` 不是稳定绑定。它是 HotSpot tiered compilation/deopt 的当前入口缓存：carrier 从 C1 换到 C2、被 `make_not_entrant`、或被 deopt 时，只会更新 carrier 自己的 `Method`，不会自动同步已经手工写过的 target。`HotSpotMethodBridge.isCurrent(link)` 只做快照校验；变成 `false` 后需要重新 link，不能继续调用旧 target。

运行期修改 `MethodFlags` 只能影响之后的编译/内联判断，不会撤销已经存在的 target nmethod、已经 inline target 的 caller，也不能取消已经排队或正在执行的编译任务。所以 target 最好在暴露给业务调用前 link，并在调用前用 `isCurrent(link)` 做快照校验。

当前 `compileNow` 验证了 macOS arm64 + Corretto 25、GraalVM JDK 25，以及 OrbStack Linux aarch64 + Corretto 25.0.1。它依赖 HotSpot-family `libjvm` 保留这些 local C++ symbol：

```text
_ZN6Method26checked_resolve_jmethod_idEP10_jmethodID
_ZN6Thread7currentEv
_ZN8WhiteBox14compile_methodEP6MethodiiP10JavaThread
_ZN6Method13get_i2c_entryEv
_ZN10BufferBlob6createEPKcj
```

macOS/aarch64 raw `byte[]` 路径还需要：

```text
_ZN2os24current_thread_enable_wxE6WXMode
```

Mach-O 文件符号表会在上述 Itanium ABI 名字前再多一个 `_`；ELF 直接使用上述名字。Windows 动态库格式是 PE/COFF，当前实现只在选到 Windows 时抛出未实现异常。

这些不是稳定 exported symbol。其他发行版、Linux stripped build 都不能假定可用。

raw `byte[]` 路径还有两个边界：

- macOS/aarch64 trampoline 直接调用 `os::current_thread_enable_wx`，它更新 HotSpot 的 per-thread JIT W^X TLS，但不是完整 `ThreadWXEnable` RAII；切换窗口必须保持极短，且不能在 `WXWrite` 中返回 Java/FFM。
- Linux/aarch64 通常命中 `libjvm` 内的 `__clear_cache`，没有复刻 HotSpot `NeoverseN1ICacheErratumMitigation` 分支；需要覆盖该 CPU erratum 时要改 ELF 的 clear-cache 策略或改为调 HotSpot 自己的路径。
