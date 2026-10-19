# KhonFontBook 源码构建

源码仓库保留 `plugin/khon-font-book`、`docs/plugin/khon-font-book` 与 `scripts/halo-fonts` 的布局。进入插件目录执行构建；根 README 为产品介绍。

## 工具与构建

准备 JDK 21、Node >=22.12.0、pnpm 10.33.0。使用仓库中的 Gradle Wrapper 与 pnpm 锁文件。

```sh
cd plugin/khon-font-book
./gradlew clean build -Pversion=0.1.0
./gradlew sourceBundle -Pversion=0.1.0
```

PowerShell 使用 `./gradlew.bat`，版本参数写为 `'-Pversion=0.1.0'`。类 Unix 系统首次解压若执行位丢失，可用 `sh ./gradlew`。源码仓库中的 gradlew 保留可执行位。

JAR 生成于插件目录的 `build/libs/`，相应源码 ZIP 生成于 `build/distributions/`。公开分发 JAR 时同时提供对应源码 ZIP，其中包含插件源码、构建文件、锁文件及固定运行依赖的 Maven 源码 JAR；首次获取依赖需要网络。构建本身不安装、启用或发布插件。

## 验证与环境

`build` 执行 Java 测试、UI 测试、类型检查和生产构建。Python 测试另需 Linux 和固定 FontTools 4.67.0 / Brotli 1.2.0；例如从仓库根目录运行：

```sh
KFB_PARSER="$PWD/plugin/khon-font-book/src/main/resources/fontmeta.py" \
  python3 -m unittest discover -s plugin/khon-font-book/src/test/python -v
```

`scripts/halo-fonts/Dockerfile` 与 requirements 提供解析运行环境示例；导入仍需配置字体专用附件策略和 external-url，详见[安装说明](app-store-listing.md)。源码不包含用户字体、站点数据或凭据。

服务端目标为 Halo 2.27.0 / Java 21，UI 使用 Vue、TypeScript、Vite ESM 与 Halo 宿主共享依赖。默认 DevTools 端口为 8091。生成客户端通过 `generateApiClient` 更新，不手工修改；不要额外打包 Halo 已提供的日志实现或 SLF4J API。
