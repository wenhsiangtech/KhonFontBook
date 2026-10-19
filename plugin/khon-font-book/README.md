# KhonFontBook

<img src="src/main/resources/cover-9dfb46d3.png" alt="KhonFontBook" width="160" />

KhonFontBook 是 Halo 的字体管理插件。支持上传字体、设置中英文字体搭配，并为主题中的标题、正文或指定元素设置字体，不需要修改主题文件。

版本 0.1.0 · 作者 KhonChi · Halo 2.27.0 · GPL-3.0

## 功能

- **字体库**：导入 WOFF2 和 TrueType TTF，查看字重、字符覆盖及 normal、italic、oblique 样式，按需预览字体。
- **中英配对**：为中文和西文分别选字体，保存为可重复使用的方案，并设置缺字时使用的回退字体。
- **主题规则**：通过 class、ID 或标签选择要修改的文字，可限定主题、主页、文章或独立页面。
- **选择器查询**：从给定选择器查找承载文字的子元素，测试哪些选择器可以覆盖主题的字体设置，再填入规则。
- **外部接入**：提供字体目录、方案、样式和引用接口，供主题或其他插件使用；也可以单独安装使用。

## 使用

1. 按[安装说明](../../docs/plugin/khon-font-book/app-store-listing.md)准备解析环境和字体存储策略，安装并启用插件。
2. 在「字体库」导入字体，填写来源与许可，检查字体预览。
3. 如需搭配中英字体，在「配对方案」中保存组合。
4. 在「主题规则」中选择主题、页面范围、字体或方案，填写选择器并保存。
5. 在插件设置中开启「主题字体应用」，刷新目标页面。

多个目标用半角逗号分隔，例如：

```css
.post-title, #page-title
```

`.article .title` 中的空格表示后代元素；`.title.featured` 表示同一元素同时有两个 class。页面范围选「主页」时，规则只在站点根路径 `/` 应用。

## 环境与范围

适用于 Halo 2.27.0、Java 21。字体导入需要 Linux、Python ≥3.11、FontTools 4.67.0、Brotli 1.2.0 和独立的 local 附件策略。Python 环境需另行安装。

支持静态单色 WOFF2 和 TrueType TTF，单文件上限 20 MiB。暂不支持 OTF、变量字体、颜色字体、字体集合、格式转换和自动子集化。

规则默认只设置字体族。主题内联样式或子元素的字体设置可能影响效果，可用选择器查询定位；iframe 和 Shadow DOM 内的文字需要单独适配。

字体附件公开访问，来源和授权说明中的部分信息也会公开。请使用有公开托管授权的字体。删除字体记录会保留附件，文件需在 Halo 附件管理中另行删除。

## 文档与反馈

[安装与使用](../../docs/plugin/khon-font-book/app-store-listing.md) · [数据处理](../../docs/plugin/khon-font-book/privacy.md) · [版本说明](../../docs/plugin/khon-font-book/release-notes.md) · [源码构建](../../docs/plugin/khon-font-book/build.md) · [问题反馈](https://github.com/wenhsiangtech/KhonFontBook/issues)

许可证：[GPL-3.0](LICENSE)。第三方组件及许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
