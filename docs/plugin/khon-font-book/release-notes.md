# KhonFontBook 0.1.0

KhonFontBook 的首个版本。作者：**KhonChi**。

- **字体库**：原格式导入静态单色 WOFF2 / TrueType TTF，查看真实字重、normal / italic / oblique 样式、字符覆盖及字体预览；记录来源与许可。
- **中英配对**：分别选择中文与西文字体，按共同字重与样式建立方案，分配字符覆盖并设置回退链。
- **主题规则**：按主题及主页、文章、独立页面范围，为 class / ID 等选择器应用字体；支持规则顺序、可选字重和显式强制覆盖。
- **选择器诊断**：查询页面中真正承载文字的后代元素，测试覆盖效果，并将选定结果填入规则草稿。
- **开放接入**：提供公开字体目录、方案与样式资源，支持外部引用登记和引用删除保护。

运行于 Halo 2.27.0 / Java 21。字体导入另需 Linux Python ≥3.11、FontTools 4.67.0、Brotli 1.2.0、独立本地附件策略及 external-url。主题注入默认关闭。

[安装与使用](app-store-listing.md) · [数据处理说明](privacy.md) · [问题反馈](https://github.com/wenhsiangtech/KhonFontBook/issues)
