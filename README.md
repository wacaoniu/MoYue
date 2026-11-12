# 墨阅 (MoYue) — Android 电子书阅读器 + 电脑版 + iPad 版

一个零第三方依赖的安卓电子书阅读器：自研解析 + WebView 渲染引擎，支持 **EPUB / PDF / TXT / CBZ** 四种格式，目标是对各种来源的电子书（包括制作粗糙的）有最好的兼容性。附带 **电脑版**（`desktop/`，本地网页应用）与 **iPad 版**（`ipad/`，PWA 网页应用）。

## 支持的格式

- **EPUB**：EPUB 2/3、nav 与 NCX 目录、嵌套小节、封面、内嵌图片/CSS、内文链接与锚点、GBK/GB18030 内容编码与 GBK 压缩包文件名、目录大小写容错、损坏条目跳过
- **PDF**：系统 PdfRenderer 按需渲染，进漫画长条模式（连续滚动 + 双指缩放），渲染结果磁盘缓存，每 20 页一个虚拟章节
- **TXT**：BOM/UTF-8/UTF-16/GB18030 自动识别；按“第X章 / Chapter N / 序章 / 楔子”等自动分章，无结构时按长度分块
- **CBZ**：漫画压缩包，图片自然排序（p2 < p10），自动进长条模式，第一页作封面
- 不支持：DRM 加密书、MOBI/AZW3（私有格式）

## 功能

- **书架**：封面展示、阅读进度百分比、最近阅读排序、长按删除、系统文件管理器「打开方式」直达阅读（已注册 epub/pdf/cbz）
- **导入**：格式自动识别（按魔数+扩展名）；SAF 文件选择器（多选）+ 投放目录自动导入（`Android/data/com.moyue.reader/files/Import/` 里的 epub/pdf/txt/cbz 会被自动收进书架）
- **漫画长条模式（webtoon 式）**：图多的书/漫画导入时自动识别并进入；整章图片零间距竖排连续滚动，直接在页面上双指缩放（无需点开图片，可拖移查看细节，菜单里有"复位缩放"），下滑到章尾自动无缝衔接下一章、上滑到顶自动回填上一章；**宽页拆分**：跨页大图可一键拆成左右两个单页（支持左起/右起，日漫右起常用），每本书独立记忆；文本/漫画模式可在菜单里随时互切
- **阅读**：
  - 点击分区翻页：左 1/3 上一屏、右 1/3 下一屏、中间呼出菜单；章末自动进下一章
  - 音量键翻页
  - 目录抽屉（当前章高亮、支持小节锚点）
  - 主题：纸张 / 米黄 / 护眼 / 夜间（CSS 变量实时切换，不重载）
  - 字号 70%–200%（WebView textZoom 全局缩放）与 衬线/无衬线 字体切换
  - 阅读进度、主题、字号、字体全部持久化，重开书自动回到上次位置
  - 沉浸式阅读（自动隐藏系统栏，呼出菜单恢复），阅读时屏幕常亮

## 电脑版

`desktop/` 目录是电脑版（本地网页应用，纯 Python 标准库服务端 + 浏览器渲染）：

- 双击 `启动墨阅.bat`（需装 Python 3），自动在浏览器打开 http://127.0.0.1:8756
- 支持 EPUB / TXT / CBZ / PDF（浏览器内 pdf.js 渲染），与安卓版同源的解析逻辑（GBK 编码、容错解包、自然排序、自动分章、漫画识别）
- 书架 / 文本阅读器（iframe 保留原书排版 + 主题字号注入）/ 漫画长条（连续滚动 + 宽页拆分 + Ctrl+滚轮缩放）/ PDF 长条（懒渲染）
- 阅读进度与拆分设置存在 `desktop/books/` 里；把电子书丢进 `desktop/收书箱/` 文件夹自动入库；也支持拖拽导入
- 键盘：←/→ 或 PgUp/PgDn 翻章翻页，Esc 回书架

## iPad 版（PWA）

`ipad/` 目录是 iPad/iPhone 版：纯前端网页应用（PWA），无需 App Store——Safari 打开部署好的网址，「添加到主屏幕」即可全屏离线使用。系统要求 iPadOS/iOS 16.4+。

- 与电脑版同源的解析逻辑（`moyue-core.js` 移植自 `moyue_desktop.py`：ZIP 解包 + GBK 文件名/内容解码、EPUB 2/3、TXT 自动分章、CBZ 自然排序、漫画识别），全部在浏览器本地完成
- 书与进度存 IndexedDB（本机浏览器存储），不经过任何服务器；Service Worker 提供离线缓存 + `/z/` 虚拟文件系统（漫画按需解压单个图片，不整本展开占内存）
- 文本模式用 Shadow DOM 内联渲染（iOS 上 iframe 滚动体验差），主题/字号实时注入；漫画长条 / 宽页拆分 / 双页合并 / 页缝与电脑版一致；PDF 用同版 pdf.js 懒渲染并做远页内存回收
- 触摸交互：点屏幕两侧翻页、中间呼出菜单，双指捏合缩放（双击复位），长按书架封面删书，安全区适配，阅读时屏幕常亮
- 本地测试：双击 `ipad/本地测试.bat`（需 Python 3）；部署：`ipad/www/` 是标准静态目录，可托管到任何静态网站（详见 `ipad/使用说明.txt`）
- 当前线上部署：https://moyue-reader-h3bd.zai.site （iPad Safari 打开 → 分享 → 添加到主屏幕）

## 已知限制

- 不支持 DRM 加密电子书（这是版权保护，任何正规阅读器都不支持）
- 竖排日文书按原书 CSS 渲染，翻页方向仍为纵向滚动

## 构建

需要 JDK 17+、Android SDK（API 35）、Gradle 8.x：

```bash
# Windows (Git Bash)
cmd //c "set JAVA_HOME=<你的JDK路径>&& gradle.bat :app:assembleDebug"
# 产物
app/build/outputs/apk/debug/app-debug.apk
```

minSdk 26（Android 8.0），targetSdk 35。

## 代码结构

```
app/src/main/java/com/moyue/reader/
├── MainActivity.kt          # 入口、导航、ACTION_VIEW 接入、音量键分发
├── data/
│   ├── EpubParser.kt        # ZIP 解包 + container/OPF/NCX/nav 解析 + 路径规范化
│   └── BookRepository.kt    # 书籍存储(meta.json)、进度、设置、投放目录扫描
├── reader/
│   ├── ReaderStyles.kt      # 主题定义 + HTML 注入引擎(CSS变量/滚动恢复/翻页JS/字符集探测)
│   └── ReaderScreen.kt      # 阅读器 UI(WebView、分区点击、菜单、目录、设置)
└── ui/
    └── LibraryScreen.kt     # 书架
```

## 测试

`testbooks/` 内含三本生成的测试书：EPUB3 中文（嵌套目录+图片+内链）、EPUB2 英文（NCX）、EPUB2 GBK 编码。推送到 `Android/data/com.moyue.reader/files/Import/` 即自动导入。
