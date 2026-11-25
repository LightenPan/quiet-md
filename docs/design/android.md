# Android 客户端规格

状态：第一期已实现（2026-11-25）。本地文件库、旧便签迁移、坚果云同步、Compose 界面、
WorkManager 周期任务和端到端持久化测试均已完成。

参考实现：`third/smartisan-notes/android`。本规格只覆盖 Android 客户端；桌面端继续按
[`nutstore-sync.md`](../nutstore-sync.md) 工作。Git、iCloud 和其他第三方云盘同步不在本期范围内。

## 1. 目标

第一期交付一个可日常使用的 Android 便签客户端：

- 便签以磁盘上的 Markdown 文件为唯一真相，不再把 Room 当作主存储。
- 文件布局与桌面 Quiet.md 兼容：文件夹就是目录，标题就是文件名。
- 可在 Android 上新建、编辑、搜索、收藏、置顶、移动和删除便签。
- 可将文件库与坚果云 WebDAV 同步，支持首次合并、冲突副本和删除保护。
- 旧版 Smartisan Notes 的 Room 便签可无损迁移到文件库。
- 不依赖 NDK、UniFFI 或第二套编辑器即可构建并测试。

成功标准：

1. 新安装、旧数据升级、进程被杀后重启都不会丢失便签。
2. 在 Android 新建或修改的 Markdown 文件可由桌面 Quiet.md 直接打开。
3. 两台设备先后修改同一文件时，至少保留两份完整内容，不静默覆盖。
4. 首次连接已有内容的坚果云文件夹时，不会自动混库。
5. JVM 单元测试覆盖文件库、旧数据迁移、WebDAV 解析和同步状态机。

## 2. 非目标

以下内容明确不在第一期：

- 坚果云以外的第三方云盘同步。
- Git、iCloud、自有服务器同步。
- 在 Android 编辑 DocxXML 富文档、画板、数据表或合并单元格。
- 完整复刻桌面端的块编辑器、拖拽和桌面快捷键。
- 把 `.quiet/` 元数据、置顶、收藏、排序和版本历史同步到其他设备。
- 用 SAF 中的任意目录作为实时文件库。第一期只使用 App 私有目录；SAF 仅用于以后导入/导出。
- 多库切换、多窗口、平板双栏、后台常驻或五分钟高频轮询。

第二期可增加：Rust/UniFFI 富文档门面、SAF 库、图片插入、分享、WorkManager 常驻同步、
自动三方合并和 DocxXML 只读渲染。

## 3. 架构

```text
Jetpack Compose
  ├─ NoteListScreen / NoteEditScreen / SettingsScreen
  ├─ NoteViewModel  ───────────────┐
  └─ Sync UI / WorkManager         │
                                   ▼
com.zhaoolee.notes.library    com.zhaoolee.notes.sync
  ├─ NoteLibrary                ├─ NutstoreWebDavClient
  ├─ Local metadata             ├─ NutstoreSyncEngine
  └─ LegacyNotesMigrator        └─ NutstoreCredentialStore
           │                              │
filesDir/Quiet.md/                 noBackupFilesDir/nutstore/
  Folder/Title.md                    state.json
  .assets/<uuid>.<ext>               removed/
```

边界：

- `library` 和 `sync` 不依赖 Compose；除 Android 凭据存储外，核心逻辑可在 JVM 单元测试中运行。
- `NoteLibrary` 是同一条用户编辑路径的唯一写入者；同步引擎只能在自己的临界区内写文件。
  两者按 canonical library root 共享同一把进程级异步锁，自动保存、改名、删除和整轮同步
  不得交错。UI 不直接写便签文件。
- `NutstoreSyncEngine` 只通过接口访问文件系统和 WebDAV，便于用内存实现做确定性测试。
- 同步状态、凭据和恢复文件永不放进可同步的文件库。

首期使用 Kotlin 文件层而不是 Rust 门面，因为 Markdown 编辑和 WebDAV 同步不需要解析
DocxXML。等第二期实现富文档只读/编辑时，再在 `NoteLibrary` 后面接入 `fdl_core`，
不改变 UI 和同步引擎的文件接口。

## 4. 目录与文件格式

### 4.1 本地库

```text
filesDir/Quiet.md/
  Welcome.md
  Work/周报.md
  .assets/2c7f....png
  .trash/2026-11-25T12-30-00_Welcome.md
```

- 可编辑文档为 UTF-8 `.md`。正文原样保存，不强制重写 Markdown。
- 文件名（不含 `.md`）是标题。标题最长 120 个字符。
- 文件夹是真实目录，层级不限；`.` 开头的目录除 `.assets` 外不展示、不同步。
- 新便签默认标题为“无标题”，冲突时依次使用“无标题 2”“无标题 3”。
- 删除先移动到 `.trash/`，第一期暂不提供“清空回收站”。
- 收藏、置顶属于本机元数据，存放在 `noBackupFilesDir/library-meta.json`，不写进 Markdown。
- `.xml`、`.excalidraw`、`.sqlite` 属于可同步文件，但第一期列表只显示、打开时提示
  “请在桌面端编辑”。不得把它们当 Markdown 重写。
- `.assets/` 中的文件参与同步；`.assets/thumbs/`、`.trash/`、`.quiet/` 不参与同步。

### 4.2 标题和路径

标题和目录名去掉首尾空白后，禁止：

- 空字符串、`.`、`..`
- `/` `\` `:` `*` `?` `"` `<` `>` `|`
- 控制字符
- Windows 保留名 `CON`、`PRN`、`AUX`、`NUL`、`COM1`…`COM9`、`LPT1`…`LPT9`

生成文件名时还要：

- 把连续空白折叠成一个空格。
- 去掉文件名末尾的 `.` 和空格。
- 以 NFC 规范化。
- 检测同级仅大小写不同的碰撞，并生成唯一后缀。
- 重命名时使用“写新文件 → flush → 原子替换/改名”的步骤；失败不丢失旧文件。

### 4.3 本机元数据

`noBackupFilesDir/library-meta.json`：

```json
{
  "v": 1,
  "notes": {
    "Work/周报.md": {
      "pinned": false,
      "favorite": true,
      "createdAt": 1764054600000
    }
  }
}
```

- 文件改名或移动时同步迁移元数据键。
- 找不到对应文件时保留条目 30 天，之后清理。
- 解析失败时把原文件改名为 `library-meta.json.corrupt-<time>`，从空元数据继续。

## 5. 旧版便签迁移

输入为现有 Room 表 `notes`：

```text
id, content, category, isPinned, isFavorite, images,
createdAt, updatedAt, footerText
```

规则：

1. 只执行一次；完成标记写入 `noBackupFilesDir/migration.json`，记录 Room 库文件大小与
   内容摘要、迁移时间和创建的文件数。
2. 不删除、不修改原 Room 数据库。迁移前把数据库复制为
   `filesDir/legacy/smartisan_notes.db.bak`。
3. `content` 原样写入 Markdown 正文，不从正文中删除标题行。
4. 标题取正文第一行去掉 Markdown 标题前缀后的前 40 个字符；为空时使用
   `便签 yyyy-MM-dd HH-mm`。同名时追加序号。
5. 非空 `category` 规范化为一级目录；非法名称使用“默认”。
6. `isPinned`、`isFavorite`、`createdAt` 写入本机元数据。
7. `images` 首期不解析，避免 JSON 尚未验证的字段造成丢数据；原始值保留在迁移报告。
8. `footerText` 是旧 App 设置，不写入每篇便签。
9. 迁移过程中任何单篇失败都记录后继续；最后报告成功、跳过和失败数量。完成标记只在全部
   可处理条目完成后写入，失败条目可在下次启动重试。
10. 如果文件库已有用户文件，迁移仍可进行，但必须使用唯一文件名，绝不覆盖。

迁移结果通过设置页的“旧便签迁移”显示：已迁移数量、备份位置、失败数量。

## 6. 便签仓库接口

`com.zhaoolee.notes.library` 提供：

```kotlin
data class LibraryNote(
    val relativePath: String,
    val title: String,
    val body: String,
    val folder: String,
    val modifiedAt: Long,
    val createdAt: Long,
    val pinned: Boolean,
    val favorite: Boolean,
    val editable: Boolean
)

class NoteLibrary(root: File, metadataFile: File, clock: Clock = Clock.systemUTC()) {
    suspend fun list(query: String = "", folder: String? = null): List<LibraryNote>
    suspend fun get(relativePath: String): LibraryNote?
    suspend fun create(title: String, folder: String = "", body: String = ""): LibraryNote
    suspend fun save(note: LibraryNote, newTitle: String = note.title): LibraryNote
    suspend fun delete(relativePath: String)
    suspend fun folders(): List<String>
    suspend fun setFavorite(relativePath: String, favorite: Boolean)
    suspend fun setPinned(relativePath: String, pinned: Boolean)
    suspend fun move(relativePath: String, newFolder: String): LibraryNote
}
```

要求：

- 所有写操作串行化；同一实例内不能出现并发写同一路径。
- 同一根目录的所有 `NoteLibrary` 实例与 `NutstoreSyncEngine` 共用一把进程级锁；持锁范围
  覆盖“读旧内容 → 判断冲突 → 原子写 → 更新元数据/同步状态”。锁按 canonical path 取，
  锁内不得再次获取同一把锁。
- `save` 检测文件在读取后被外部修改：不覆盖，抛出 `NoteConflictException`，UI 提供“另存为”
  或“重新载入”。
- `list` 按“置顶优先、修改时间倒序、拼音标题升序”排序。首期可使用
  `Collator.getInstance(Locale.SIMPLIFIED_CHINESE)`；数字和拉丁字母自然排在汉字之前。
- 搜索匹配标题、正文和目录，不区分大小写。
- 读取限制 100 MiB；超过限制的 Markdown 显示错误而不是 OOM。
- 读取失败的单篇文件记录警告并跳过，不让整个列表失败。

## 7. 编辑与保存

### 7.1 编辑体验

- 打开便签显示标题输入框和正文 Markdown 文本编辑器。
- 正文使用等宽或适合中文的正文样式，保留换行和缩进。
- 工具栏首期只保留：返回、收藏、置顶、分享纯文本、删除。
- 新建便签立即创建空文件并进入编辑，避免返回时没有实体。
- 删除前确认；删除后回列表并显示“可在回收站恢复”的提示。
- `.xml` 和 `.excalidraw` 只读打开，显示明确提示，不提供编辑框。

### 7.2 保存规则

1. 标题或正文变化后防抖 500 ms 保存。
2. 返回、Activity `ON_STOP`、打开其他便签前立即 flush。
3. 保存中再次编辑时，最多保留一个待保存版本；上一次完成后再写最新版本。
4. 标题变化导致文件改名时，先写新文件，再删除旧文件，最后迁移本机元数据。
5. 保存失败时保留编辑内容，顶部显示“未保存”，允许重试或复制全文。
6. 旋转或进程重建时通过 `SavedStateHandle`/`rememberSaveable` 恢复未保存的标题和正文。

## 8. 坚果云 WebDAV 同步

### 8.1 配置

默认值：

```text
server      https://dav.jianguoyun.com/dav/
remoteRoot  Quiet.md
```

用户输入：

- WebDAV 地址
- 坚果云登录邮箱
- 第三方应用密码
- 远程文件夹（可多级，如 `Quiet.md/android`）

校验与桌面端一致：

- 生产环境必须使用 HTTPS。Debug 构建额外允许 HTTP，仅用于本地 WebDAV 冒烟测试；
  Release 使用 Android 默认的明文网络策略，坚果云默认地址为 HTTPS。
- 远程目录不能包含 `..` 或非法文件名。
- 远程根不存在时逐级 `MKCOL`。
- 同一文件库同时只允许一个同步后端；本期仅坚果云。

### 8.2 凭据

- 密码存 Android Keystore 生成的 AES-GCM 密钥加密后的密文；密文可放
  `SharedPreferences`，密钥不可导出。
- 服务器、账号和远程目录可明文存本机配置，但不得写日志。
- 退出账号删除密码和同步状态，不删除本地或远程文件。
- Android 9 以下不使用 `EncryptedSharedPreferences` 新 API；最低 API 26 直接使用
  Keystore + AES/GCM。

### 8.3 状态

`noBackupFilesDir/nutstore/state.json`：

```json
{
  "v": 1,
  "server": "https://dav.jianguoyun.com/dav/",
  "username": "name@example.com",
  "remoteRoot": "Quiet.md",
  "scanComplete": true,
  "folders": ["Work"],
  "files": {
    "Work/周报.md": {
      "etag": "\"abc\"",
      "size": 1234,
      "modified": "Wed, 25 Nov 2026 10:00:00 GMT",
      "sha256": "..."
    }
  }
}
```

- 账号、服务器或远程目录变化时拒绝复用旧状态，要求“重新扫描”。
- 状态损坏时改名为 `state.json.corrupt-<time>`，下一次走首次同步。
- 远端突然为空、而状态和本地都有文件时暂停同步并提示，不把删除传播到本地。

### 8.4 WebDAV 行为

- 使用 OkHttp，Basic Auth 放在请求头，不拼进 URL。
- 扫描优先 `PROPFIND Depth: infinity`；失败或达到 750 项时逐层 `Depth: 1`。
- 写入已有远端文件带 `If-Match: <上一 ETag>`。
- 新建远端文件带 `If-None-Match: *`。
- 远端没有 ETag 时退回 `size + Last-Modified` 判断变化。
- 删除文件带 `If-Match`；创建目录用 `MKCOL`；移动优先 `MOVE` + `Overwrite: F`。
- 单文件上限 100 MiB，超过 50 MiB 给出警告。
- 请求超时：扫描 120 秒，下载/上传 300 秒，普通操作 60 秒。
- 401/403 归类为认证失败，409/412/423 为冲突，429/503 为限流或暂不可用。

### 8.5 首次同步

扫描本地和远端后：

| 本地 | 远端 | 行为 |
|---|---|---|
| 空 | 有 | 下载 |
| 有 | 空 | 上传 |
| 空 | 空 | 标记完成 |
| 有 | 有 | 返回 `NeedsInitialChoice`，不写任何文件 |

用户选择：

- **保留本机并上传**：仅当远端为空；否则拒绝。
- **下载并替换**：仅当本地为空；否则拒绝。
- **合并两边**：两边都保留。同名同内容只记一条；同名不同内容保留本机主文件，把远端内容
  写成冲突副本并上传，再把本机主文件上传到原路径。

### 8.6 日常同步

每个文件比较三份状态：本轮本地哈希、上一轮本地哈希、远端 ETag。

| 本地变化 | 远端变化 | 行为 |
|---|---|---|
| 否 | 否 | 不动 |
| 是 | 否 | 上传 |
| 否 | 是 | 下载并原子替换 |
| 是 | 是，内容相同 | 只更新状态 |
| 是 | 是，内容不同 | 本机主文件保留，远端版本写成冲突副本；随后上传本机 |
| 新增 | — | 上传，必要时先建目录 |
| 本地删除 | 远端未变 | 删除远端 |
| 本地删除 | 远端已变 | 恢复远端到本机，避免丢更新 |
| 远端删除 | 本地未变 | 文件移到本地恢复区，不直接永久删除 |
| 远端删除 | 本地已变 | 保留本地并重新上传 |

首期采用“保守冲突副本”，不自动做 Markdown 三方合并。冲突名为：

```text
<标题>（冲突 · <设备名> · yyyy-MM-dd HH-mm）.<扩展名>
```

如已存在则追加 ` (2)`、` (3)`。冲突副本既是本地普通文件，也上传到远端。

改名识别：若旧路径消失、新路径出现，且本地旧文件哈希与状态一致、旧远端签名与新远端签名
一致，则用 `MOVE` 更新远端，而不是删除后重传。无法确定时按删除/新增处理。

### 8.7 触发策略

- 设置页“立即同步”随时可用。
- App 回到前台时，如果距离上次成功同步超过 30 分钟，可自动同步一次。
- 保存后不立即上传每个按键；最多在 2 分钟后触发一次。
- WorkManager 周期任务最短 30 分钟，需网络连接；不使用五分钟轮询。
- 首次大同步或用户手动同步可显示前台通知；本期若未实现通知，仅在设置页显示进度。

## 9. UI

### 9.1 便签列表

- 保持现有暖白纸感和主题选择。
- 顶部：标题“Quiet.md”、搜索、收藏筛选、设置。
- 文件夹筛选使用横向 chip；“全部”排第一。
- 卡片显示标题、正文摘要、修改时间、收藏和置顶状态。
- 列表空、加载、错误、跳过文件分别有明确状态。
- 下拉刷新只重扫本地文件，不触发网络同步。

### 9.2 编辑页

- 标题和正文是两个明确输入区；标题不能为空，失焦时回退旧标题并提示。
- 自动保存状态显示“正在保存 / 已保存 / 未保存”。
- 分享使用 `ACTION_SEND` 发送标题和正文纯文本。
- 底部标识可保留旧版设置，但不写入文档。

### 9.3 设置

保留现有外观、主题和底部标识，增加：

- **文件库**：路径、文档数、回收站大小、打开迁移报告。
- **坚果云**：连接/断开、服务器、账号、远程文件夹、立即同步、上次同步、结果统计。
- **存储与恢复**：旧便签迁移、失败原因、打开恢复区。

首次同步只在两边都已有内容时弹窗，提供“合并两边”和“稍后”；默认焦点在“合并两边”。
“保留本机并上传”和“下载并替换”只在对侧为空时自动执行，不会在混库弹窗中提供。

## 10. 错误与保护

- 所有下载和保存先写同目录临时文件，`flush + fsync` 后原子改名。
- 便签文件缺失时，列表刷新而不是崩溃。
- 任何同步错误都不得把主文件替换为空文件。
- 远端目录出现 `..`、控制字符、仅大小写不同路径或同一路径既文件又目录时停止同步。
- 不在 UI 或日志中输出应用密码、完整 Authorization 头或文件正文。
- 共享 `ContentResolver` URI 时先复制到缓存并设置 `FileProvider`。
- 旧 Room 数据库、迁移备份、同步状态和凭据都不参与 Android Auto Backup。

## 11. 构建与依赖

- `minSdk = 26`，`targetSdk = 35`，JVM target 17。
- 保留 Compose BOM、Lifecycle、Navigation、Room（仅用于读取旧数据）、DataStore 和 Gson。
- 新增 `okhttp`、`androidx.work:work-runtime-ktx`、JUnit 4、OkHttp MockWebServer。
- 不引入第三方云盘 SDK 或 OAuth 库。
- 提交 Gradle Wrapper（`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`）。
- 包名暂时保留 `com.zhaoolee.notes` 以便继续使用现有原型；公开发布前必须确认代码许可并改为
  Quiet.md 自有包名。

构建命令：

```text
cd third/smartisan-notes/android
gradlew.bat testDebugUnitTest
gradlew.bat connectedDebugAndroidTest
gradlew.bat assembleDebug
```

## 12. 测试

### 12.1 文件库

- 新建、读取、修改、改名、移动、删除和恢复。
- 标题非法字符、Windows 保留名、重名和仅大小写重名。
- 中文排序、数字优先、置顶优先。
- 外部修改冲突不覆盖。
- 原子写失败保留旧文件。
- `.xml` 只读、100 MiB 上限、损坏 UTF-8。

### 12.2 迁移

- 空正文、单行、多行、空分类、非法分类、同名标题。
- 已有同名用户文件时不覆盖。
- 重复运行不重复创建。
- 原 Room 数据库保持字节不变。

### 12.3 WebDAV

- 解析 207 Multi-Status、URL 解码、中文路径、集合和深度扫描。
- Basic Auth 在请求头、创建/更新条件头正确。
- 750 项回退、非法路径拒绝、认证/限流错误分类。

### 12.4 同步状态机

用内存文件系统和假 WebDAV 覆盖：

- 首次四种分支和三种用户选择。
- 仅本地改、仅远端改、两边相同、两边不同。
- 新增、删除、远端删除但本地已改、本地删除但远端已改。
- 远端突然为空时暂停。
- 改名识别与冲突副本。
- 状态损坏后重新扫描。

### 12.5 手工验收

1. 在 Android 新建 `测试/你好.md`，用桌面打开并编辑，再同步回 Android。
2. 桌面和 Android 同时改同一篇，确认两端都有冲突副本且主文件完整。
3. 断网编辑、退出、重开，内容仍在；恢复网络后同步成功。
4. 旧版便签迁移后每篇正文逐字一致。
5. 进程被系统杀掉后重新打开，最近 500 ms 内的编辑不丢。
6. `NotePersistenceTest` 在模拟器上创建便签、输入正文、等待自动保存、返回列表并重建
   Activity 后仍能读到正文。

## 15. 首期验证记录

2026-11-25 在 Windows、Android SDK 35、`homex_landscape_api35_verify` 模拟器上验证：

- JVM：51 项单元测试通过，0 failures / 0 errors。
- 仪器测试：1 项 `NotePersistenceTest` 通过；实际输入、500 ms 自动保存、返回列表和
  Activity 重建后仍可从 Markdown 文件读到正文。
- 默认空库启动、新建、编辑、移动、搜索/筛选和强制停止后重启均通过。
- 本地 WsgiDAV 4.4.1 真实 HTTP：(1) 本机 1 个文件首次同步上传成功；
  (2) 修改远端后“立即同步”下载 1 个文件；(3) 两端同时修改时，本机主文件保留，
  远端版本生成冲突副本并上传，界面报告“冲突副本 1”。
- 尚未使用真实坚果云账号联调；认证、限额和 TLS 行为由单元测试及桌面端既有实现覆盖。

## 13. 实施切片

### A. 文件库与迁移

交付 `library` 包、Room 迁移、JVM 测试和 Gradle Wrapper。完成后 UI 可以在不联网的情况下
完成全部本地便签操作。

### B. 坚果云内核

交付 `sync` 包、OkHttp WebDAV、状态机、凭据存储和单元测试。完成后可用设置页传入配置和
假 UI 做端到端同步。

### C. 界面接线

交付文件库 ViewModel、编辑页自动保存、列表、设置页同步区、首次选择对话框、前台/周期触发
和手工验收记录。

### D. 后续

- Rust/UniFFI 只读 DocxXML。
- SAF 导入/导出与图片插入。
- Markdown 三方合并。
- 公开包名、许可确认、签名和发布流水线。

## 14. 许可与发布门槛

`third/smartisan-notes` 当前没有 LICENSE/COPYING，且是独立 Git 仓库。第一期可把它作为
内部原型继续实现，但在公开发布、合并进入主仓库或分发 APK 前必须：

1. 确认原 `zhaoolee/notes` 和当前仓库的代码所有权与许可；
2. 在 `THIRD_PARTY.md` 记录来源和许可；
3. 为 Quiet.md 使用自己的 application ID、图标、应用名和签名；
4. 移除无法确认授权的素材。

未完成以上事项时，不得对外称其为 Quiet.md 正式 Android 版。
