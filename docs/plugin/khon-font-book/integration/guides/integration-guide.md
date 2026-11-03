# KhonFontBook 开放接入（v1alpha1）

目标为 Halo 2.27.0，同源、静态单色 WOFF2 / TrueType TTF。无需依赖另一个插件或安装 SDK。调用方只保存稳定业务 ID；文件 URL、CSS 摘要和内部 Extension 结构不作为业务主键。这里的“公开”是匿名可读；登记及替换仍须单独授权。

安装前置与维护操作见[用户说明](../../console/guides/user-guide.md)。权限验证覆盖真实账号会话，未覆盖 PAT 创建和轮换的完整运行流程。

## 读取契约

公开根路径 `/apis/api.font-book.tungwenblog.dev/v1alpha1`，仅 GET：

| 路径 | 返回 |
| --- | --- |
| `/capabilities` | apiVersion、主题注入开关、Head 与集成标记、协调/检查边界 |
| `/families?page=1&size=20`、`/families/{id}` | 字体族稳定 ID、显示名、公开数据 revision、字形元数据、stylePath |
| `/schemes?page=1&size=20`、`/schemes/{id}` | 可用方案、稳定 ID、公开数据 revision、中英族 ID、latin-cjk-v1、真实 weight/style、回退链、stylePath |
| `/families/{id}/style`、`/schemes/{id}/style` | apiVersion、kind/id、CSS revision、href、className、variable、最后一级 fallback |
| `/styles/{sha256}.css` | 主题与公开接入共享的不可变 CSS 快照 |

列表 size 为 1..100；page≥1。仅返回启用且至少有可发布字形的字体族；方案须启用且中英两侧均可用。停用、缺失或全部不可用的单项/最新样式返回 404。字形字段为 id、familyName/subfamily、weight/style、characterCount/glyphCount、embeddingRights、coverageRanges、contentSha256、url、source/license/category；不返回附件身份、所有者、策略配置、解析器路径、后台版本/诊断或 Extension DTO。启用字体及其来源/授权说明会公开，请勿在说明中填写秘密。

公开数据 revision 是公开字段的摘要；样式 revision 是实际 UTF-8 CSS 的 SHA-256，两者可以不同。例如授权说明变化会改变前者，但没有理由改变 CSS。发现接口均 no-store；CSS 为 immutable、ETag/304，未知/淘汰/重启后的旧摘要可能 404。旧 URL 绝不返回新字节。新页面/重新渲染时重新获取 stylePath，不能永久保存 href。静态字体属性和字符覆盖遵循当前字体支持范围，不声称支持可变轴、字体转换或完整中文覆盖；元数据可用也不证明物理文件当前可读。

## 最小浏览器接入

在自己的 ES 模块或 async 渲染函数中调用下列同源代码。API 不接收选择器；服务端只生成稳定 class `kfb-{业务ID}`，以及描述中给出的 CSS 变量。字体族保留宿主字重/样式；方案使用配置的真实字体声明和中英 unicode-range，宿主最终匹配仍受浏览器字体选择规则影响。默认不改变字号等排版。

```js
const root = "/apis/api.font-book.tungwenblog.dev/v1alpha1";

// 调用前先执行该元素上一次返回的 cleanup；失败时保持系统字体。
async function applyFont(element, kind, id) {
  if (!["families", "schemes"].includes(kind) ||
      !/^(family|scheme)-[a-zA-Z0-9-]{1,90}$/.test(id)) return () => {};
  let ownedLink;
  try {
    const r = await fetch(`${root}/${kind}/${encodeURIComponent(id)}/style`, {
      credentials: "omit", redirect: "error", cache: "no-store"
    });
    if (!r.ok || !/^application\/json\b/i.test(r.headers.get("content-type") || ""))
      throw Error("KhonFontBook unavailable");
    const m = await r.json();
    if (m.apiVersion !== "v1alpha1" || m.id !== id || m.className !== `kfb-${id}` ||
        !/^\/apis\/api\.font-book\.tungwenblog\.dev\/v1alpha1\/styles\/[a-f0-9]{64}\.css$/.test(m.href))
      throw Error("Unsupported manifest");
    const href = new URL(m.href, location.origin).href;
    let link = [...document.querySelectorAll('link[rel="stylesheet"]')]
      .find(link => link.href === href);
    if (!link) {
      link = document.createElement("link");
      link.rel = "stylesheet"; link.href = href;
      link.dataset.khonFontBookIntegration = m.revision;
      ownedLink = link;
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(Error("CSS timeout")), 10000);
        link.onload = () => { clearTimeout(timer); resolve(); };
        link.onerror = () => { clearTimeout(timer); reject(Error("CSS failed")); };
        document.head.append(link);
      });
    } else if (link.dataset.khonFontBookIntegration) {
      // 切回曾加载的版本时，使当前版本位于旧集成样式之后。
      document.head.append(link);
    }
    element.classList.add(m.className);
    // 共享 stylesheet 不因单个元素卸载而删除。
    return () => element.classList.remove(m.className);
  } catch {
    ownedLink?.remove();
    return () => {};
  }
}

const element = document.querySelector("#my-own-text");
let cleanup = await applyFont(element, "families", "family-替换为公开列表中的ID");
// 切换、目标禁用或重新渲染时：
// cleanup();
// cleanup = await applyFont(element, "schemes", "scheme-实际ID");
```

将基础回退放在父容器，例如 `<div style="font-family:system-ui,sans-serif"><p id="my-own-text">样文 ABC 123 中文，。</p></div>`；不要给目标元素设置普通内联 font-family 再期待 class 规则覆盖它。

并发组件可在调用方已有的资源加载器中按 href 共享加载 Promise；上例是单组件最小流程，不构成共享 SDK。同一元素不要同时应用两个字体目标 class。字体文件 404 时由 font-display:swap 与回退链保持文字可读；CSS 加载成功不代表字体已经成功加载。调用方可用 computedStyle、document.fonts.check/实际渲染检查自己的元素。

## 与 Head 注入协调

主题规则开关只控制 主题 Head，不关闭显式公开接入。关闭开关仍允许读取公开字体及目标 class 样式；字体/方案自身停用会关闭最新发现。主题 Head 用 data-khon-font-book，接入样式用 data-khon-font-book-integration；按确切 href 去重，不因为看见任一 Head link 就省略所有目标样式。两者可能同时存在，但选择器和命名互不替代；接入方不自行重放主题/页面规则，不从 URL 猜测主题预览上下文。

公开目标样式不按主题/文章过滤，只作用于显式附加该 class 的元素；主题与页面规则仍由 P5 的真实上下文处理。编辑器接入若以后获授权，也应把 class 限制在正文区域，不作用于整个 Console。已有页面不会因停用实时撤回样式；新渲染应重新发现、先移除旧 class，旧不可变 CSS 和热字体缓存不会自动消失。外部 HTML 缓存也由调用方负责失效。

## 登记与所有权

管理根路径 `/apis/console.api.font-book.tungwenblog.dev/v1alpha1`。给接入方专用 Halo 账号只授予 `role-template-khon-font-book-references`，不需要字体 view/manage、文章管理或原始资源权限。对应 UI 权限名 khon-font-book:references:manage。使用 Halo 已认证会话，或由该用户自己管理、具有同等限定角色的 PAT；令牌仅在服务端/Secret 中保管，禁止写入公开页面或前端包。

sourceId 固定取服务器认证 Principal.name；Halo 2.27.0 PAT 也以其所属 username 为认证名。同账号的多个令牌/会话是同一来源，轮换令牌不改变引用归属。两个独立接入方须用不同专用账号；不得复用或转让来源账号名称。客户端 sourceId/owner 字段不会授予所有权。来源账号被删除不会释放登记；撤销账号前应先处理自己的登记，遗失来源须由管理员审计处理，不能让另一来源代撤。

| 操作 | 契约 |
| --- | --- |
| GET /external-references | 仅列当前来源；返回 sourceId、key、version、targetKind/targetId、contentRevision |
| PUT /external-references/{key} | targetKind=family/scheme、targetId、必填 contentRevision；首次不带 version；相同目标与内容版本重复提交不写入、不递增版本 |
| DELETE /external-references/{key}?version=N | 只撤自己的键；现存须版本一致；已不存在返回 204，重复撤销幂等 |
| POST /replacements | 字体管理权限；仅迁移插件控制的方案/规则，详见下节 |

key 是调用方稳定的内容/字段键，例如 post-稳定内容ID:body-font，限 128 个字母、数字及 . _ : -。内部存储名由认证来源与 key 的完整 SHA-256 构成。A/B 使用相同 key 是两个登记；B 的撤销不会影响 A。匿名不能写；登记账号没有原始 fontreferences 写权限。

以下命令由服务端执行，ROOT/TOKEN 来自运行环境，ID 来自公开列表。不要把 TOKEN 放入源码、命令历史或浏览器：
```sh
# 读取是匿名的
curl --fail "$ROOT/apis/api.font-book.tungwenblog.dev/v1alpha1/families?page=1&size=20"
# 登记自己的外部字段；重复发送同一 JSON 得到同一登记版本
curl --fail -X PUT "$ROOT/apis/console.api.font-book.tungwenblog.dev/v1alpha1/external-references/post-one:body-font" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data '{"targetKind":"family","targetId":"family-实际ID","contentRevision":"external-revision-1"}'
# 先确认自己的内容已不再使用该字段，再撤销；N 取 GET/PUT 返回的 version
curl --fail -X DELETE "$ROOT/apis/console.api.font-book.tungwenblog.dev/v1alpha1/external-references/post-one:body-font?version=N" \
  -H "Authorization: Bearer $TOKEN"
```

登记与外部文章保存不在同一事务中。新使用应先登记再保存实际内容，保存失败可撤销预登记；迁移应先给新目标使用另一个临时键登记以保护它，保存/核实外部内容后确认旧键迁移，最后撤销临时键。遇到失败保留保护并重试，不先撤旧登记制造删除窗口。撤销是来源的责任声明，不是插件扫描或证明外部内容已删除。

## 替换及显式迁移确认

管理 POST /replacements 的 JSON：
```json
{"kind":"family","oldId":"family-旧ID","newId":"family-新ID","oldVersion":0,"newVersion":0}
```

kind 仅 family/scheme，同类型、不同 ID；版本来自管理目录。新目标须可发布；字体族替换还须满足所有引用方案（包括停用方案）的真实字重/样式/字符要求，以及直接规则的显式字重。字体族替换更新方案的两侧 ID 和直接规则目标；方案替换更新直接规则目标。不移动旧族字形、不改附件、不删除旧资源、不修改任何外部登记或文章。

先检查受影响关系，再在同一 FontService 写锁内逐条保存。单条关系更新与版本检查一致，但 Halo 多 Extension 更新不是数据库事务：
- INTERNAL_COMPLETE：插件内待迁移关系及直接外部旧目标登记均已清零；不是外部内容扫描完成。
- EXTERNAL_CONFIRMATION_REQUIRED：内部已更新，旧目标仍有已登记外部使用。
- PARTIAL：部分内部写入未完成；updated/remaining 列出真实结果，旧目标保留。修复原因、重新读取版本后重试，只处理剩余旧关系。不能只看 HTTP 200。
- 预检不兼容或版本冲突返回 409，不开始写入。

实际外部内容保存并核实后，由**原登记来源** PUT 相同 key：
```json
{
  "version": 0,
  "targetKind": "family",
  "targetId": "family-新ID",
  "contentRevision": "external-revision-2",
  "previousKind": "family",
  "previousId": "family-旧ID",
  "confirmation": "CONTENT_UPDATED"
}
```

必须匹配当前登记 version、旧目标，并提交不同内容版本；缺失确认返回 409。成功后相同请求重试幂等。contentRevision 是来源的持久化版本/摘要声明，插件不能证明它真实对应外部正文；文档迁移完成应由接入方自己的保存/读取验证证明。新旧目标在确认期间都应通过登记保护。没有自动别名或任意跨来源迁移。

字体族直接登记保护其全部字形的删除/移动；方案登记保护方案删除，方案内部关系继续保护字体。资产已有字形关系同样阻止删除。停用不释放登记，未登记的 HTML/class/文件 URL 使用不能检测。管理员绕过受控 API、手工改底层记录或在存储侧删附件，不在这一引用保证内；同实例写锁不等于多节点分布式事务。

## 缺少插件与兼容边界

插件停用/未安装时发现路由可能返回 404，或由 Halo 安全链返回登录 302；跟随后的 HTML 200 不是有效 JSON/CSS。示例禁止重定向并校验 Content-Type/apiVersion，失败保留系统字体；不要只判断 200。插件停用清空进程内 CSS 快照；重新启用后重新发现，不依赖旧链接仍可取。

v1alpha1 仍为首版契约：字段新增须兼容，改变标识/所有权/字段语义时另立版本并给迁移说明；本阶段没有发布 SDK、第二个插件、特定编辑器或线上 API。当前只验证本机 Halo 和浏览器；跨域字体、CDN、分布式快照及通用主题/浏览器覆盖不在已验证范围。
