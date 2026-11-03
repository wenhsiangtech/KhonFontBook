# KhonFontBook 源码构建

工作区中的源码位于 `plugin/khon-font-book`，文档位于 `docs/plugin/khon-font-book`。进入插件目录执行构建；插件 README 为产品介绍，文档入口为[工程与模块索引](../../README.md)。

## 工具与构建

准备 JDK 21、Node >=22.12.0、pnpm 10.33.0。使用仓库中的 Gradle Wrapper 与 pnpm 锁文件。

```sh
cd plugin/khon-font-book
./gradlew clean build -Pversion=0.2.0
./gradlew sourceBundle -Pversion=0.2.0
```

PowerShell 使用 `./gradlew.bat`，版本参数写为 `'-Pversion=0.2.0'`。类 Unix 系统首次解压若执行位丢失，可用 `sh ./gradlew`。源码仓库中的 gradlew 保留可执行位。

JAR 生成于插件目录的 `build/libs/`，相应源码 ZIP 生成于 `build/distributions/`。公开分发 JAR 时同时提供对应源码 ZIP，其中包含插件源码、构建文件、锁文件及固定运行依赖的 Maven 源码 JAR；首次获取依赖需要网络。构建本身不安装、启用或发布插件。

## 验证与环境

`build` 执行 Java 测试、UI 测试、类型检查和生产构建，并从 `src/parser/java` 构建独立元数据 worker。FontBox 及 Commons Logging 只进入 worker JAR，不进入 Halo 宿主类路径。内置解码器位于 `src/main/resources/parser/linux-x86_64/decoder`，通常无需重新编译。

2026-11-03 源码修复的已记录验证为 73 项 Java、18 项 UI 测试。重新构建时须检查实际命令输出；类型检查或空测试集不能代替业务验收。Linux 字体导入、普通账号权限、主题实际加载及生命周期仍需 Halo 实例验证，编译成功不证明这些运行路径通过。

如需从固定源码重建解码器，在 Linux x86_64、Ubuntu 24.04 / GCC/G++ 13.3.0 的相同工具链中，从插件目录执行（输出目录需已存在）：

```sh
sh src/parser/native/build.sh /absolute/build-directory
cp /absolute/build-directory/decoder src/main/resources/parser/linux-x86_64/decoder
```

构建会生成链接映射与 ELF 依赖说明，用于核对运行库来源；这些诊断输出无需放入插件资源，应移至工程外构建目录后只复制 `decoder`。源码目录包含固定的 WOFF2/Brotli 必要源码及原始声明，严格 cmap 修改保留在 FontBox 原包名内。使用不同工具链时必须重新核对最低 glibc 符号版本和运行库声明；不沿用旧二进制的兼容结论。

安装运行不调用 Python、pip 或编译器，也不下载解析组件。当前解析运行范围为 Linux x86_64、glibc ≥2.38、Java 21、系统 `/usr/bin/prlimit` 和可写/可执行临时目录。FontStorage在首次访问工作台或导入前自动准备专用附件策略，沿用有效的已有配置；附件读取使用Halo自身配置的external-url。源码不包含用户字体、站点数据或凭据。

服务端目标为 Halo 2.27.0 / Java 21，UI 使用 Vue、TypeScript、Vite ESM 与 Halo 宿主共享依赖。默认 DevTools 端口为 8091。生成客户端通过 `generateApiClient` 更新，不手工修改；不要额外打包 Halo 已提供的日志实现或 SLF4J API。
