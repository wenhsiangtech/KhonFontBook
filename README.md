# KhonFontBook

<img src="plugin/khon-font-book/src/main/resources/cover-9dfb46d3.png" alt="KhonFontBook" width="160" />

KhonFontBook 是 Halo 的字体管理插件。支持上传字体、设置中英文字体搭配，并为主题中的标题、正文或指定元素设置字体，不需要修改主题文件。

版本 0.2.0 · 作者 KhonChi · Halo 2.27.0 · GPL-3.0

## 功能

- **字体库**：上传 WOFF2 和 TrueType TTF，或从专用存储的已有附件导入；查看真实字重、字符覆盖及 normal、italic、oblique 样式，按需预览字体。
- **中英配对**：为中文和西文分别选字体，保存为可重复使用的方案，并设置缺字时使用的回退字体。
- **主题规则**：通过 class、ID 或标签选择要修改的文字，可限定主题、主页、文章或独立页面。
- **选择器查询**：从给定选择器查找承载文字的子元素，测试哪些选择器可以覆盖主题的字体设置，再填入规则。
- **外部接入**：提供字体目录、方案、样式和引用接口，供主题或其他插件使用；也可以单独安装使用。

## 使用

1. 安装并启用插件，打开字体工作台检查解析与存储状态。无需填写解释器或策略，已有有效策略继续沿用。详见[安装说明](docs/plugin/khon-font-book/release/guides/app-store-listing.md)。
2. 在「字体库」导入字体，填写来源与许可，检查字体预览。
3. 如需搭配中英字体，在「配对方案」中保存组合。
4. 在「主题规则」中选择主题、页面范围、字体或方案，填写选择器并保存。
5. 在插件设置中开启「主题字体应用」，刷新目标页面。

普通账号需要「查看字体资源」权限才能访问工作台；导入、编辑或删除还需要「管理字体资源」权限。字体解析与存储就绪后才可导入。完整流程见[快速开始](docs/plugin/khon-font-book/console/guides/quick-start.md)。

多个目标用半角逗号分隔，例如：

```css
.post-title, #page-title
```

`.article .title` 中的空格表示后代元素；`.title.featured` 表示同一元素同时有两个 class。页面范围选「主页」时，规则只在站点根路径 `/` 应用。

## 环境与范围

适用于 Halo 2.27.0、Java 21，当前解析运行范围为 Linux x86_64、glibc ≥2.38，系统提供 `/usr/bin/prlimit`，临时目录可写且允许执行。解析组件随插件提供，无需安装 Python、FontTools 或另配解释器；其他系统和架构尚未验证。新安装在首次访问工作台或导入前自动准备专用 local 附件策略，已有有效配置保留。

支持静态单色 WOFF2 和 TrueType TTF，单文件上限 20 MiB。暂不支持 OTF、变量字体、颜色字体、字体集合、格式转换和自动子集化。

规则默认只设置字体族。主题内联样式或子元素的字体设置可能影响效果，可用选择器查询定位；iframe 和 Shadow DOM 内的文字需要单独适配。

字体附件公开访问，来源和授权说明中的部分信息也会公开。请使用有公开托管授权的字体。删除字体记录会保留附件，文件需在 Halo 附件管理中另行删除。

## 文档与反馈

[快速开始](docs/plugin/khon-font-book/console/guides/quick-start.md) · [完整用户说明](docs/plugin/khon-font-book/console/guides/user-guide.md) · [安装与升级](docs/plugin/khon-font-book/release/guides/app-store-listing.md) · [版本说明](docs/plugin/khon-font-book/release/guides/release-notes.md)

[数据处理](docs/plugin/khon-font-book/release/guides/privacy.md) · [源码构建](docs/plugin/khon-font-book/release/guides/build.md) · [开放接入](docs/plugin/khon-font-book/integration/guides/integration-guide.md) · [文档索引](docs/plugin/khon-font-book/README.md) · [问题反馈](https://github.com/wenhsiangtech/KhonFontBook/issues)

许可证：[GPL-3.0](LICENSE)。第三方组件及许可见 [THIRD_PARTY_NOTICES.md](plugin/khon-font-book/THIRD_PARTY_NOTICES.md)。
