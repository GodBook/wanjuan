# 书源兼容清单 · 引擎 0.1

## 支持的格式

导入单个 JSON 对象或数组，文件 / 粘贴 / HTTPS 链接。保留原始字段。未知扩展不会被误标为已支持。最多 1,000 个书源、8MB 配置。

| 范围 | 支持 |
|---|---|
| 元数据 | bookSourceName / bookSourceUrl / bookSourceGroup / enabled |
| 搜索 | searchUrl，{{key}} / {{searchKey}} / {{page}}（首版为第 1 页） |
| 请求 | GET，URL 后附 JSON 指定 POST、body、type；全源 header |
| 搜索规则 | bookList / name / author / bookUrl / intro / coverUrl |
| 详情规则 | tocUrl / intro |
| 目录规则 | chapterList / chapterName / chapterUrl / nextTocUrl |
| 正文规则 | content / nextContentUrl |
| HTML | jsoup CSS，常用 class. / id. / tag. 前缀，@text / @textNodes / @html / @href 等 |
| JSON | JSONPath，$ 或 @json: 前缀 |
| XPath | // 或 @XPath: 前缀，节点集与属性 |
| 组合 | 字段值的 || 回退、&& 连接 |
| 正则 | ##pattern##replacement，拒绝部分高开销语法；输入长度有限制 |
| JavaScript | 字段级 @js: 纯运算，result 输入；单次 1 秒指令预算、2.5 秒调用超时 |

目录最多 30 页、正文最多 20 页；重复链接终止，防止分页循环。超过范围应拆分或修正书源。HTTP 必须在该书源设置中显式允许。书源不能请求私有网段；单书源不执行任意 Java 或 Android 方法。

## 不支持

- 任意 `java.*`、`Packages`、Android API、脚本联网与动态代码依赖。
- WebView 网页渲染、登录、验证码处理、Cookie 持久会话。
- `@get:` / `@put:`、复杂模板表达式、自定义阅读宿主 API。
- 无限搜索翻页、完整发现页规则、全部 Legado CSS 索引语法。

书源导入时的分析是静态提示，**不等于网站在线或规则已验证**。检测按钮使用用户输入的书名验证搜索链。正文和目录通过实际加载验证。

## 最小示例

```json
{
  "bookSourceName": "自己的内容站点",
  "bookSourceUrl": "https://example.org",
  "searchUrl": "/search?q={{key}}",
  "ruleSearch": {
    "bookList": ".book", "name": "a@text", "author": ".author@text", "bookUrl": "a@href"
  },
  "ruleBookInfo": { "tocUrl": ".toc@href" },
  "ruleToc": { "chapterList": ".chapter", "chapterName": "a@text", "chapterUrl": "a@href" },
  "ruleContent": { "content": "main@html" }
}
```

示例域名只是格式演示，不是可用书源。测试使用 MockWebServer 的固定 HTML 响应，不依赖第三方小说网站。
