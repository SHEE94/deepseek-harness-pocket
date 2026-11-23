# termux/proot 交叉编译配方报告（arm64-v8a · ubuntu-24.04 CI + NDK r26/27）

侦查时间：2026-11-13。来源均为当日实时抓取的 GitHub 页面原文（非记忆）。
- 主构建文件：https://github.com/termux/proot/blob/master/src/GNUmakefile （全文已读，8111 B，blob sha 5ade279…）
- Termux 官方打包参数：https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh （全文已读）
- https://github.com/termux/proot/blob/master/AGENTS.md 、README.md、src/arch.h、src/compat.h、src/execve/enter.c、src/execve/shebang.c、src/execve/aoxp.c、src/cli/cli.c、src/path/temp.c、src/loader/loader.c、src/extension/ashmem_memfd/ashmem_memfd.c、src/extension/sysvipc/sysvipc_shm.c
- 依赖包配方：packages/libtalloc/build.sh、packages/libandroid-shmem/build.sh
- 完整文件树（recursive tree API）：https://api.github.com/repos/termux/proot/git/trees/master?recursive=1
- packages/proot 目录列表：仅 `build.sh` 与 `termux-chroot` 两个文件，**没有任何 .patch 文件**（build.sh 也未引用 patch）。

版本锚点：Termux 当前打包 `TERMUX_PKG_VERSION="5.1.107.102"`，源码为
`TERMUX_PKG_SRCURL=https://github.com/termux/proot/archive/v${TERMUX_PKG_VERSION}.zip`，
`TERMUX_PKG_SHA256=30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852`。
CI 建议固定该 tag（master 可能前进；master 的 GNUmakefile 已含 "Android NDK r29+ compatibility" 注释，见 D）。

---

## A. GNUmakefile 的变量与目标

**执行目录**：`make -C src`（Termux 的 `TERMUX_PKG_EXTRA_MAKE_ARGS="-C src PROOT_WITH_LIBANDROID_SHMEM=true"`；AGENTS.md：「./src/: source code, execute make here」）。makefile 头部还支持外树构建：
```
# If you want to build outside of the source tree, use the -f option:
#     make -f ${SOMEWHERE}/proot/src/GNUmakefile
VPATH := $(dir $(lastword $(MAKEFILE_LIST)))
```
外树时 .o 生成在当前目录（规则里 `@mkdir -p $(dir $@)`）。

**工具链变量**（全部 env 兼容，逐字）：
```
CC       ?= $(CROSS_COMPILE)gcc
LD       = $(CC)
STRIP    ?= $(CROSS_COMPILE)strip
OBJCOPY  ?= $(CROSS_COMPILE)objcopy
OBJDUMP  ?= $(CROSS_COMPILE)objdump
CPPFLAGS += -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$(VPATH)
CFLAGS   += -Wall -Wextra -O2
LDFLAGS  += -ltalloc -Wl,-z,noexecstack
```
- **没有 AR**、没有静态库归档步骤，全为直接链接。
- **关键语义陷阱**：`CPPFLAGS/CFLAGS/LDFLAGS` 用的是 `+=`。**只能经环境传入**；若在 make 命令行上写 `LDFLAGS=...`，会整体废除 makefile 的 `+=`（含 `-ltalloc -Wl,-z,noexecstack`）。`CC/STRIP/OBJCOPY/OBJDUMP` 是 `?=`，环境传入即可。`LD = $(CC)` 会跟随 CC。
- `V=1` 关闭静默输出。`.DEFAULT_GOAL = proot`；`all: proot`。
- 构建期还有 4 个**解析期探测**（都用 $(CC)/$(LD) 执行，交叉编译时同样走交叉工具链）：
  1. `define_from_arch.h`：`$(CC) $1 -E -dM -DNO_LIBC_HEADER $(SRC)/arch.h | grep -w …`，提取 `HAS_LOADER_32BIT`、`HAS_POKEDATA_WORKAROUND`、`LOADER_ARCH_CFLAGS*`、`LOADER_ADDRESS*`；
  2. `.check_process_vm` / `.check_seccomp_filter`：**编译+链接**测试（`.check_%.res: .check_% → if [ -e $< ]` 判断产物存在），不运行目标二进制 → 交叉编译安全，结果写入 build.h 的 `HAVE_PROCESS_VM` / `HAVE_SECCOMP_FILTER`；
  3. `BUILD_ID_NONE`、`ROSEGMENT`：向 `/dev/null` 链接探测 lld 是否接受 `-Wl,--build-id=none` / `--rosegment`，失败则省略（注释明言 "Android NDK r29+ compatibility"）。
- `VERSION`：`build.h` 由 `git describe` 生成；**若无 git 则不定义 VERSION**，Termux 用 `-DVERSION=\"…\"` 补（见 B）。`git` 缺失本身安全（`2>/dev/null` + 空值分支）。

**目标与产物**：
| 目标 | 产物 | 说明 |
|---|---|---|
| `proot`（默认） | `src/proot` | 链接全部 OBJECTS |
| loader | `src/loader/loader` | **始终 `-static -nostdlib`**：`LOADER_LDFLAGS$1 += -static -nostdlib -Wl$(BUILD_ID_NONE),-Ttext=$(LOADER_ADDRESS$1)$(ROSEGMENT),-z,noexecstack`；编译用 `-fPIC -ffreestanding` |
| `loader.exe` | `src/loader.exe` | `$(STRIP)` 后的副本，`.INTERMEDIATE`（构建后不留存） |
| `loader/loader-wrapped.o` | objcopy `--input-target=binary` 把 loader.exe 变成 .o 内嵌进 proot |
| `install` | `install -D proot $(DESTDIR)$(BINDIR)/proot`（`PREFIX ?= /usr/local`，`BINDIR ?= $(PREFIX)/bin`）；`ifdef PROOT_UNBUNDLE_LOADER` 时再 `install -D loader/loader $(PROOT_UNBUNDLE_LOADER)/loader`（及 `loader32`） |
| `clean distclean` | 清理 | |

**loader 是否进主程序由 `PROOT_UNBUNDLE_LOADER` 决定**（逐字）：
```
ifdef PROOT_UNBUNDLE_LOADER
  CFLAGS += "-DPROOT_UNBUNDLE_LOADER=\"$(PROOT_UNBUNDLE_LOADER)\""
proot: | loader/loader
  ...
else
  OBJECTS += loader/loader-wrapped.o
```
即：不定义（默认）→ loader 编译进 proot 单文件；定义 → loader 为独立文件，proot 里烤入该目录宏。

---

## B. Android 专属宏（从 Termux build.sh 逐字引用）

build.sh 全文中与 define 有关的全部内容包括：
```
TERMUX_PKG_DEPENDS="libandroid-shmem, libtalloc"
TERMUX_PKG_BUILD_IN_SRC=true
TERMUX_PKG_EXTRA_MAKE_ARGS="-C src PROOT_WITH_LIBANDROID_SHMEM=true"

# Install loader in libexec instead of extracting it every time
export PROOT_UNBUNDLE_LOADER=$TERMUX_PREFIX/libexec/proot

termux_step_pre_configure() {
	CPPFLAGS+=" -DARG_MAX=131072 -DVERSION=\\\"${TERMUX_PKG_VERSION}\\\""
}
```
结论：
- **Termux 官方没有传任何 `NO_LOADER*` 或 `LINUX_UAPI` 类宏**。整个 GNUmakefile 与已读源码（compat.h 全文、enter.c、aoxp.c、cli.c、arch.h）中也不存在这两种 define。GNUmakefile 里出现过的宏只有：makefile 内建的 `-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE`、探测用的 `-DNO_LIBC_HEADER`（内部机制，勿手动传）、`-DWITH_LIBANDROID_SHMEM`（由 `PROOT_WITH_LIBANDROID_SHMEM=true` 触发）、`-DPROOT_UNBUNDLE_LOADER="…"`。
- 我们需要照抄的只有两个 CPPFLAGS：`-DARG_MAX=131072`（`aoxp.c` 用 `char tmp[ARG_MAX]`、`cli.c` 用 `char string[ARG_MAX]`，其 include 注释即 `#include <linux/limits.h> /* ARG_MAX, */`；Termux 特意补它，说明该头在 Android 工具链下不给 ARG_MAX——NDK 是否同样缺失见 F-未证实）和 `-DVERSION="…"`（tarball 无 git 时必须）。
- `WITH_LIBANDROID_SHMEM` 只影响 sysvipc 扩展的 SysV shm 后端：`sysvipc_shm.c` 中 `#if defined(WITH_LIBANDROID_SHMEM)` 才调用 `shmget/shmctl/libandroid_shmat_fd`（经 `--shm-helper` 子进程）；不定义时走 proot 自己的 `sysvipc_shm_do_allocate()`：先 `SYS_memfd_create`，失败回退 `/dev/ashmem`。**与我们壳 APK 场景无涉，可完全不开、不链 libandroid-shmem。**

---

## C. 依赖：talloc 必需，未内联；无需 -landroid-glob

- **libtalloc 必需且未 vendor**：GNUmakefile 无条件 `LDFLAGS += -ltalloc`；recursive tree 全列表 grep `talloc|vendor` **零命中**（无内联源码）；AGENTS.md 逐字：「The project is written in C and has two dependencies: - libtalloc - libandroid-shmem (optional, needs-DWITH_LIBANDROID_SHMEM)」。talloc.h 在 enter.c/temp.c/aoxp.c/cli.c 等大量出现。
- Termux 的 libtalloc 配方（packages/libtalloc/build.sh 逐字要点）：waf 交叉编译
```
./configure --prefix=$TERMUX_PREFIX --disable-rpath --disable-python --cross-compile --cross-answers=cross-answers.txt
...
termux_step_post_make_install() {
	cd $TERMUX_PKG_SRCDIR/bin/default
	$AR rcu libtalloc.a talloc*.o
	install -Dm600 libtalloc.a $TERMUX_PREFIX/lib/libtalloc.a
}
```
（即 Termux 环境下同时产出 libtalloc.so 和 **libtalloc.a**；我们静态链只需要后者。）
- **libandroid-shmem 可选**（不定义 PROOT_WITH_LIBANDROID_SHMEM 就完全不引用，见 B）。
- **-landroid-glob 不需要**：已读全部文件中无 glob() 使用；Termux build.sh 也没有加任何 bionic 特供库（DEPENDS 只有上面两个）。
- `ashmem_memfd.c` 整个文件被 `#if defined(__ANDROID__) || defined(__BIONIC__)` 包住，非 Android 时是空翻译单元，不需要额外处理。

---

## D. NDK clang 直接编 bionic 目标的配方骨架

```bash
#!/usr/bin/env bash
set -euo pipefail
PROOT_TAG=v5.1.107.102
PROOT_SHA256=30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852
API=26                                   # Termux 全局默认 API 级别见 F-未证实；21+ 理论可行，26 起无历史包袱
export ANDROID_NDK_HOME=/opt/android-ndk-r26.3   # r26/r27 皆可；master makefile 的 r29 适配是自动探测，r26/27 只是少加 --rosegment
TOOLCHAIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64

# 0) 静态 libtalloc.a（照抄 Termux 思路：waf cross + ar 打包 .o）
#    clone talloc-2.5.0（SRCURL/SHA 见 packages/libtalloc/build.sh），写同款 cross-answers.txt
./configure --prefix=/usr --disable-rpath --disable-python \
    --cross-compile --cross-answers=cross-answers.txt   # 在 talloc 源码目录, env 见下
make
mkdir -p "$PWD/prebuilt/lib" && ar rcu "$PWD/prebuilt/lib/libtalloc.a" bin/default/talloc*.o

# 1) 编 proot（flag 一律走环境，不要放 make 命令行 —— 见 A 的 += 陷阱）
export CC=$TOOLCHAIN/bin/aarch64-linux-android${API}-clang   # wrapper 名自带 --target+API+sysroot
export STRIP=$TOOLCHAIN/bin/llvm-strip
sudo apt-get install -y binutils-aarch64-linux-gnu           # OBJCOPY/OBJDUMP 需要能认识 aarch64
export OBJCOPY=aarch64-linux-gnu-objcopy
export OBJDUMP=aarch64-linux-gnu-objdump
export CPPFLAGS="-DARG_MAX=131072 -DVERSION=\"${PROOT_TAG}\""
export LDFLAGS="-static -L${TALLOC_PREBUILT}/lib"            # makefile 会再追加 -ltalloc -Wl,-z,noexecstack
unset PROOT_UNBUNDLE_LOADER                                   # 单文件内嵌 loader（壳 APK 推荐，见 E）
make -C src V=1
file src/proot        # 期望: statically linked, ARM aarch64
```
如 NDK 的 `aarch64-*-clang -m32`（arm32 loader 通道，见 F）在你的 NDK 版本上不可用且不需要 arm32 guest，可用 GNU make 的命令行覆盖语义禁用 32 位 loader（命令行变量优先于 makefile 内 `$(eval … := …)` 赋值）：`make -C src HAS_LOADER_32BIT=`。enter.c 中对应符号声明为 `extern unsigned char WEAK _binary_loader_m32_exe_start;`，缺定义可链接（推导，未实测）。

**与 Termux 官方做法的差异 / 风险**：
1. **静态 vs 动态**：Termux 的 proot 是动态（链 libtalloc.so、libandroid-shmem.so，运行期从 $PREFIX/lib 解析）；我们要 `-static`。全静态 bionic 属 NDK 支持路径，但 proot 的静态产物 Termux 官方未验证过（风险：talloc 静态初始化符号顺序；用 `--version` 冒烟）。
2. **未开 shmem**：Termux 开 `PROOT_WITH_LIBANDROID_SHMEM=true`；我们不开 → 少一个依赖，代价是 guest SysV shm 不与宿主 Android 进程共享（proot-distro 常规用法不需要）。
3. **不 unbundled**：Termux 烤 `$PREFIX/libexec/proot` 路径；我们默认内嵌（差异的行为学结论见 E）。
4. **`--rosegment` 探测**：master 为 NDK r29+ 加的探测在 r26/27 上探测失败即自动跳过（逻辑是 `&& echo ',--rosegment'`），无碍；若用旧 tag 则根本没有该段。
5. OBJIFY 依赖 `$(OBJDUMP) -f cli/cli.o` 输出中出现 `file format …` 与 `architecture:` 行（`grep 'file format' | awk '{print $4}'`、`grep architecture | cut -f 1 -d , | awk '{print $2}'`）。GNU/llvm objdump 都能给出这两行，但 GNU objdump 默认只认宿主架构 → 所以装 `binutils-aarch64-linux-gnu` 显式指定（该项属推导，CI 首跑请核对 `V=1` 输出）。
6. minSdk：Termux 仓库全局默认（非本包 build.sh 内容）我未读到具体数值 → 列入未证实。21→26 的建议基于：`HAVE_PROCESS_VM`/`HAVE_SECCOMP_FILTER` 是链接期探测、探测不过自动降级（`proot --version` 会打印 "built-in accelerators: process_vm = …, seccomp_filter = …"，cli.c 逐字），所以低 API 不会编不过，只会少加速器。

---

## E. 产物清单与 loader 运行时定位（决定性证据）

`src/execve/enter.c` 中 `get_loader_path()` 全文已读，逐字：
```c
static inline const char *get_loader_path(const Tracee *tracee)
{
#if defined(PROOT_UNBUNDLE_LOADER)
#if defined(HAS_LOADER_32BIT)
	if (IS_CLASS32(tracee->load_info->elf_header)) {
		return getenv("PROOT_LOADER_32") ?: PROOT_UNBUNDLE_LOADER "/loader32";
	}
#endif
	return getenv("PROOT_LOADER") ?: PROOT_UNBUNDLE_LOADER "/loader";
#else
	static char *loader_path = NULL;
#if defined(HAS_LOADER_32BIT)
	static char *loader32_path = NULL;
	if (IS_CLASS32(tracee->load_info->elf_header)) {
		loader32_path = loader32_path ?: getenv("PROOT_LOADER_32") ?: extract_loader(tracee, true);
		return loader32_path;
	}
	else
#endif
	{
		loader_path = loader_path ?: getenv("PROOT_LOADER") ?: extract_loader(tracee, false);
		return loader_path;
	}
#endif
}
```
bundled 分支的 `extract_loader()`：`open_temp_file(NULL, "prooted")` 写入内嵌 blob（`_binary_loader_exe_start/end`），`fchmod u+rx`，再经 `readlink_proc_pid_fd(getpid(), fd, path)` 取 `/proc/self/fd/...` 路径执行。临时目录 = `getenv("PROOT_TMP_DIR") ?: P_tmpdir`（temp.c 逐字），失败提示语即「Please set PROOT_TMP_DIR env. variable to an alternate location」。

**结论（对壳 APK 的 files/runtime/… 布局）**：
- **推荐 bundled 单文件构建**（不定义 PROOT_UNBUNDLE_LOADER）。loader 在编译期被 objcopy 进 proot 二进制，运行时解到临时目录执行——**没有任何烤死的安装路径**，`files/runtime/proot/proot` 放哪都能跑。Termux build.sh 的注释「Install loader in libexec **instead of extracting it every time**」恰好证明"解包执行"本来就是 Android 上的原生可行路径（unbundle 只是省每次 exec 的重解包开销）。
- 运行时**必须给 proot 传 `PROOT_TMP_DIR=<应用私有且可执行的目录>`**（如 `files/runtime/tmp`）：Android 无 /tmp，P_tmpdir 在 bionic 的取值未经核实（F），且该目录必须可执行（代码里显式 `access(path, X_OK)` 校验并提示 noexec 错误）。
- CI 收集物（bundled 模式）：
  1. `src/proot` → APK `files/runtime/proot/proot`（唯一必需文件）；
  2. rootfs 不变（`files/runtime/rootfs`）。
- 若选 unbundled：额外收集 `src/loader/loader`（install 规则拷贝的是**未 strip 的 loader/loader**，不是 loader.exe）→ 放 `files/runtime/proot/loader`；此时若不用编译期宏，APK 在 exec proot 时设 `PROOT_LOADER=/data/data/<pkg>/files/runtime/proot/loader`（env 优先级高于烤入路径，见上引文）。arm32 guest 才需要 `loader32`/`PROOT_LOADER_32`。
- 附加行为注意：sysvipc 扩展的 shm-helper 用 `execl("/proc/self/exe", "proot", "--shm-helper", NULL)`（sysvipc_shm.c 逐字）——依赖 /proc/self/exe 可读可执行，APK 数据目录满足；仅在 guest 使用 SysV shm 时触发。
- aarch64 固定装载地址：`LOADER_ADDRESS 0x5400000000`（arch.h，注释解释为适配 Android 常见 39-bit 用户空间、置于内核 PIE 装载起点之下），与 APK 布局无关，无需处理。

---

## F. 未证实清单（不以记忆填空；每条附已读到的原文行）

1. **NDK r26/27 的 `aarch64-linux-android<API>-clang -m32` 能否产出 arm32 loader**（arch.h 对 ARM64 定义了 `#define HAS_LOADER_32BIT true`，makefile 因此必建 `loader-m32`）。旁证：Termux 用同款 GNUmakefile+NDK clang 交叉构建且 `make install` 规则会装 `$(PROOT_UNBUNDLE_LOADER)/loader32`，说明该组合在其 CI 可用；但我没有直接读到 NDK -m32 的文档/日志。缓解：`make -C src HAS_LOADER_32BIT=`（GNU make 命令行覆盖语义 + makefile 内为 `$(eval … := …)` 普通赋值；WEAK 符号见 enter.c `extern unsigned char WEAK _binary_loader_m32_exe_start;`）。
2. **bionic/NDK 的 `P_tmpdir` 取值**：只读到 temp.c 的 `temp_directory = getenv("PROOT_TMP_DIR"); if (temp_directory == NULL) temp_directory = P_tmpdir;`。Android 无 /tmp 属常识，但 P_tmpdir 具体宏值未读 → 运行时务必显式设 PROOT_TMP_DIR。
3. **`-DARG_MAX=131072` 在 NDK 下是否严格必需**：读到的是 aoxp.c `#include <linux/limits.h> /* ARG_MAX, */` 与 Termux 补 define 的事实；NDK sysroot 的 linux/limits.h 是否提供 ARG_MAX 未读。照抄 Termux 的 `-DARG_MAX=131072` 是零风险选项。
4. **无 git 时 VERSION 缺失是否直接编译失败**：读到 makefile `CHECK_VERSION`（git describe 失败则不写 VERSION）与 cli.c `printf("%s %s\n\n", cli->logo, cli->version)`；`cli->version` 初始化点（proot.h/proot.c 内 `= VERSION`）未读。照抄 `-DVERSION="…"` 即绕开。
5. **Termux 仓库全局默认 API level（minSdk）**：packages/proot/build.sh 未含该项，未读 docs/build.sh 全局变量。报告里 API 26 是建议值而非 Termux 实证值。
6. **llvm-objdump/llvm-objcopy 直接替代 GNU 版的可行性**：OBJIFY 解析逻辑（A-5）基于已读 makefile 文本推导；建议 CI 首跑用 `V=1` 核对 `GEN` 行。
7. **proot 主程序在 21~23 低 API 下 `.check_process_vm/.check_seccomp_filter` 的实际通过情况**：探测机制已证（链接期、不执行），但 bionic 各 API 下符号可见性未逐一验证；用 `proot --version` 的 accelerators 行作为 CI 冒烟判据即可（cli.c 逐字：`built-in accelerators: process_vm = %s, seccomp_filter = %s`）。
8. **staticbionic 全静态 + talloc 静态 .o 打包在 lld 下无符号冲突**：Termux 的 `$AR rcu libtalloc.a talloc*.o` 配方是现成的，但他们主链接仍用 .so。列入 CI 冒烟项（`proot --version` + 起一个 /bin/echo）。

---
*报告人：proot-recon（子代理）。所有引文均来自上述 URL 的当日抓取内容。*
