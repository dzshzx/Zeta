# 第三方组件

Zeta 本身按 GPL-3.0 授权（[LICENSE](LICENSE)），Copyright © 2026 dzshzx。APK 里还带着下面这些第三方组件，各自按它们的许可证分发。
应用里的「设置 › 关于 › 开源许可」列的是同一份，点开一项能看版权行和许可证全文；全文也在
[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/)（GPL-3.0 是构建时从仓根 LICENSE 拷进去的）。

这份文件由单元测试 `ThirdPartyTest` 生成，不手改。依赖或条目变了以后测试会失败，照它的提示把 `app/build/THIRD_PARTY.md` 拷过来。

- 「随包的其他组件」是手写的条目，在 `app/src/main/kotlin/com/dzshzx/zeta/core/about/ThirdParty.kt`。
  测试核对它们和仓里的实际情况一致：`helper/go.mod`、`gradle/libs.versions.toml` 里的版本，字体文件里的版权和版本，从 Eta 拷来的文件还在。
- 「依赖库」由 [licensee](https://github.com/cashapp/licensee) 在构建时从运行时依赖生成，打进 APK 的 `assets/licenses/artifacts.json`。
  许可证的允许名单在 `app/build.gradle.kts`，不在名单里或认不出的构建失败；POM 只写了网址的许可证和补上的版权行在 `ThirdParty.kt`。

## 随包的其他组件

### Eta

| 版本 | 许可证 | 上游 |
|---|---|---|
| 2026-11-21 | GPL-3.0-only | https://github.com/Mangi-11/Eta |

Zeta 的 Markdown 渲染（ui/markdown）、侧栏骨架、自适应页面布局、等宽字体的定义、对话框的接口和数字助理的浮窗是从 Eta 拷来再改写的，按 GPL-3.0 使用；所以 Zeta 也按 GPL-3.0 发布。

    Copyright © 2026 蛮吉 (Mangi-11)

文件：

- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/MarkdownMathParser.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/SmoothTextReveal.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/StreamingMarkdownRestoreState.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/TexMathDelimiters.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/components/ConversationPaneState.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/components/ConversationPaneMotion.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/ChatMarkdown.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/MarkdownContent.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/MarkdownDocumentBuilder.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/MarkdownInline.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/MarkdownMathLayoutCache.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/MathDisplay.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/MarkdownModel.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/MarkdownStyle.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/StaticMarkdownDocumentCache.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/StreamingGfmParser.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/StreamingMarkdownState.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/markdown/StreamingMarkdownStateCache.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/components/ConversationSidePaneScaffold.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/layout/AdaptivePageLayout.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/components/MonoFont.kt`
- `shared/src/commonMain/kotlin/com/dzshzx/zeta/ui/components/coui2/Dialogs.kt`
- `app/src/main/kotlin/com/dzshzx/zeta/ui/markdown/MarkdownMathContent.kt`
- `app/src/main/kotlin/com/dzshzx/zeta/ui/markdown/RaTeXMath.kt`
- `app/src/main/kotlin/com/dzshzx/zeta/assistant/AssistantPanel.kt`
- `app/src/main/kotlin/com/dzshzx/zeta/assistant/AssistantSurface.kt`
- `app/src/main/kotlin/com/dzshzx/zeta/assistant/AssistantComposer.kt`
- `app/src/main/kotlin/com/dzshzx/zeta/assistant/AssistantSuggestionRow.kt`
- `app/src/main/kotlin/com/dzshzx/zeta/assistant/AssistantEdgeGlow.kt`
- `app/src/main/kotlin/com/dzshzx/zeta/assistant/AssistantReply.kt`

### JetBrains Mono NL

| 版本 | 许可证 | 上游 |
|---|---|---|
| 2.304 | OFL-1.1 | https://github.com/JetBrains/JetBrainsMono |

代码、命令和日志用的等宽字体（无连字版的 Regular、Bold），未修改。

    Copyright 2020 The JetBrains Mono NL Project Authors (https://github.com/JetBrains/JetBrainsMonoNL)

文件：

- `app/src/main/res/font/jetbrains_mono_regular.ttf`
- `app/src/main/res/font/jetbrains_mono_bold.ttf`

### KaTeX 字体

| 版本 | 许可证 | 上游 |
|---|---|---|
| 随 RaTeX 0.1.16 | OFL-1.1 | https://github.com/KaTeX/KaTeX |

RaTeX 0.1.16 排公式用的字体，随它的 AAR 打进 assets/fonts/（20 个 KaTeX_*.ttf），未修改。

    Copyright (c) 2009-2010, Design Science, Inc. (<www.mathjax.org>)
    Copyright (c) 2014-2018 Khan Academy (<www.khanacademy.org>),
    with Reserved Font Names KaTeX_AMS, KaTeX_Caligraphic, KaTeX_Fraktur, KaTeX_Main, KaTeX_Math, KaTeX_SansSerif, KaTeX_Script, KaTeX_Size1, KaTeX_Size2, KaTeX_Size3, KaTeX_Size4, KaTeX_Typewriter.

### RaTeX 原生库里的 Rust 代码

| 版本 | 许可证 | 上游 |
|---|---|---|
| 随 RaTeX 0.1.16 | Apache-2.0 AND MIT AND Unicode-3.0 | https://github.com/erweixin/RaTeX/blob/v0.1.16/Cargo.lock |

RaTeX 的原生库 lib/arm64-v8a/libratex_ffi.so 静态链接的 Rust 标准库和 crate（RaTeX 自己的代码见 RaTeX Android 一条）。

| 部分 | 版本 | 许可证 | 版权行 |
|---|---|---|---|
| Rust 标准库（core、alloc、std，含 hashbrown、gimli、addr2line、miniz_oxide、rustc-demangle） | 1.100.0 | MIT OR Apache-2.0 |  |
| aho-corasick | 1.1.5 | Unlicense OR MIT | Copyright (c) 2015 Andrew Gallant |
| bit-set | 0.8.0 | Apache-2.0 OR MIT |  |
| bit-vec | 0.8.0 | Apache-2.0 OR MIT |  |
| bytes | 1.12.1 | MIT | Copyright (c) 2018 Carl Lerche |
| cesu8 | 1.1.0 | Apache-2.0 OR MIT |  |
| cfg-if | 1.0.5 | MIT OR Apache-2.0 |  |
| combine | 4.6.9 | MIT | Copyright (c) 2015 Markus Westerlind |
| fancy-regex | 0.20.0 | MIT | Copyright 2015 The Fancy Regex Authors. |
| itoa | 1.0.18 | MIT OR Apache-2.0 |  |
| jni | 0.21.1 | MIT OR Apache-2.0 |  |
| jni-sys | 0.3.1, 0.4.1 | MIT OR Apache-2.0 |  |
| log | 0.4.34 | MIT OR Apache-2.0 |  |
| memchr | 2.8.3 | Unlicense OR MIT | Copyright (c) 2015 Andrew Gallant |
| phf | 0.13.1 | MIT | Copyright (c) 2014-2022 Steven Fackler, Yuki Okushi |
| phf_shared | 0.13.1 | MIT | Copyright (c) 2014-2022 Steven Fackler, Yuki Okushi |
| regex | 1.13.1 | MIT OR Apache-2.0 |  |
| regex-automata | 0.4.18 | MIT OR Apache-2.0 |  |
| regex-lite | 0.1.9 | MIT OR Apache-2.0 |  |
| regex-syntax | 0.8.11 | MIT OR Apache-2.0 |  |
| serde | 1.0.229 | MIT OR Apache-2.0 |  |
| serde_core | 1.0.229 | MIT OR Apache-2.0 |  |
| serde_json | 1.0.154 | MIT OR Apache-2.0 |  |
| siphasher | 1.0.4 | MIT OR Apache-2.0 |  |
| thiserror | 1.0.69 | MIT OR Apache-2.0 |  |
| tinyvec | 1.13.3 | Zlib OR Apache-2.0 OR MIT |  |
| unicode-normalization | 0.1.26 | (MIT OR Apache-2.0) AND Unicode-3.0 |  |
| zmij | 1.0.23 | MIT | David Tolnay |

### Go 标准库与运行时

| 版本 | 许可证 | 上游 |
|---|---|---|
| 1.27.4 | BSD-3-Clause | https://go.dev/ |

zeta-helper 和 claude-switch 是 Go 程序，各编成 linux 的 arm64、amd64 两份打进 assets/zeta-helper/、assets/claude-switch/；直连远程主机的 zeta-ssh 也是，编成 android/arm64 打进 lib/arm64-v8a/libzeta_ssh.so。都静态链接 Go 标准库和运行时。

    Copyright 2009 The Go Authors.

### github.com/egoist/mygo

| 版本 | 许可证 | 上游 |
|---|---|---|
| v0.10.0 | MIT | https://github.com/egoist/mygo |

zeta-helper 只链接其中的多语言包（i18n）和它的文字数据，按客户端的界面语言出字，静态链接进 assets/zeta-helper/linux-arm64、linux-amd64。

    Copyright (c) 2026 MyGo contributors

### golang.org/x/text

| 版本 | 许可证 | 上游 |
|---|---|---|
| v0.44.0 | BSD-3-Clause | https://go.googlesource.com/text |

Go 团队维护的文字编码库，zeta-helper 读 GB18030 文本时用，静态链接进 assets/zeta-helper/linux-arm64、linux-amd64。

    Copyright 2009 The Go Authors.

### github.com/dzshzx/claude-switch

| 版本 | 许可证 | 上游 |
|---|---|---|
| v0.0.3 | MIT | https://github.com/dzshzx/claude-switch |

给 Claude Code 冷切换多个 Claude 账号的程序，账号页用它。按 helper/go.mod 的 tool 编成单独的程序（未修改），打进 assets/claude-switch/linux-arm64、linux-amd64，第一次用时装进 Linux 一侧；其中认账号的 account 包也静态链接进 assets/zeta-helper/linux-arm64、linux-amd64。

    Copyright (c) 2026 dzshzx

### github.com/dzshzx/agent-usage

| 版本 | 许可证 | 上游 |
|---|---|---|
| v0.0.2 | MIT | https://github.com/dzshzx/agent-usage |

统计 Claude Code 各账号用量、估算每周额度的工具。zeta-helper 只链接其中的 reading 包（未修改），把收到的每周额度记进 Linux 一侧 agent-usage 的数据目录，静态链接进 assets/zeta-helper/linux-arm64、linux-amd64。

    Copyright (c) 2026 dzshzx

### golang.org/x/crypto

| 版本 | 许可证 | 上游 |
|---|---|---|
| v0.57.0 | BSD-3-Clause | https://go.googlesource.com/crypto |

Go 团队维护的加密库，zeta-ssh 用它的 SSH 客户端直连远程主机（密钥交换、主机签名的核对、通道），静态链接进 lib/arm64-v8a/libzeta_ssh.so。

    Copyright 2009 The Go Authors.

## 依赖库

| 组件 | 版本 | 许可证 | 构件 | 上游 |
|---|---|---|---|---|
| Activity | 1.13.0 | Apache-2.0 | `androidx.activity:activity` | https://cs.android.com/androidx/platform/frameworks/support |
| Activity Compose | 1.13.0 | Apache-2.0 | `androidx.activity:activity-compose` | https://cs.android.com/androidx/platform/frameworks/support |
| Activity Kotlin Extensions | 1.13.0 | Apache-2.0 | `androidx.activity:activity-ktx` | https://cs.android.com/androidx/platform/frameworks/support |
| Android App Startup Runtime | 1.1.1 | Apache-2.0 | `androidx.startup:startup-runtime` | https://cs.android.com/androidx/platform/frameworks/support |
| Android Arch-Common | 2.2.0 | Apache-2.0 | `androidx.arch.core:core-common` | https://cs.android.com/androidx/platform/frameworks/support |
| Android Arch-Runtime | 2.2.0 | Apache-2.0 | `androidx.arch.core:core-runtime` | https://cs.android.com/androidx/platform/frameworks/support |
| Android Graphics Path | 1.0.1 | Apache-2.0 | `androidx.graphics:graphics-path` | https://cs.android.com/androidx/platform/frameworks/support |
| Android Support DynamicAnimation | 1.0.0 | Apache-2.0 | `androidx.dynamicanimation:dynamicanimation` | http://source.android.com |
| Android Support Library core utils | 1.0.0 | Apache-2.0 | `androidx.legacy:legacy-support-core-utils` | http://source.android.com |
| Android Support Library Document File | 1.0.0 | Apache-2.0 | `androidx.documentfile:documentfile` | http://source.android.com |
| Android Support Library Interpolators | 1.0.0 | Apache-2.0 | `androidx.interpolator:interpolator` | http://source.android.com |
| Android Support Library loader | 1.0.0 | Apache-2.0 | `androidx.loader:loader` | http://source.android.com |
| Android Support Library Local Broadcast Manager | 1.0.0 | Apache-2.0 | `androidx.localbroadcastmanager:localbroadcastmanager` | http://source.android.com |
| Android Support Library Print | 1.0.0 | Apache-2.0 | `androidx.print:print` | http://source.android.com |
| Android Tracing | 1.2.0 | Apache-2.0 | `androidx.tracing:tracing` | https://cs.android.com/androidx/platform/frameworks/support |
| AndroidX Autofill | 1.0.0 | Apache-2.0 | `androidx.autofill:autofill` | http://source.android.com |
| AndroidX Futures | 1.1.0 | Apache-2.0 | `androidx.concurrent:concurrent-futures` | http://source.android.com |
| androidx.core:core-viewtree | 1.0.0 | Apache-2.0 | `androidx.core:core-viewtree` | https://cs.android.com/androidx/platform/frameworks/support |
| androidx.customview:poolingcontainer | 1.0.0 | Apache-2.0 | `androidx.customview:customview-poolingcontainer` | https://cs.android.com/androidx/platform/frameworks/support |
| Annotation | 1.9.1 | Apache-2.0 | `androidx.annotation:annotation`<br>`androidx.annotation:annotation-jvm` | https://cs.android.com/androidx/platform/frameworks/support |
| Checker Qual | 3.33.0 | MIT | `org.checkerframework:checker-qual` | https://github.com/typetools/checker-framework.git |
| collections | 1.5.0 | Apache-2.0 | `androidx.collection:collection`<br>`androidx.collection:collection-jvm` | https://cs.android.com/androidx/platform/frameworks/support |
| Collections Kotlin Extensions | 1.5.0 | Apache-2.0 | `androidx.collection:collection-ktx` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Animation | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.animation:animation` | https://github.com/JetBrains/compose-multiplatform |
| Compose Animation | 1.11.2 | Apache-2.0 | `androidx.compose.animation:animation`<br>`androidx.compose.animation:animation-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Animation Core | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.animation:animation-core` | https://github.com/JetBrains/compose-multiplatform |
| Compose Animation Core | 1.11.2 | Apache-2.0 | `androidx.compose.animation:animation-core`<br>`androidx.compose.animation:animation-core-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Foundation | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.foundation:foundation` | https://github.com/JetBrains/compose-multiplatform |
| Compose Foundation | 1.11.2 | Apache-2.0 | `androidx.compose.foundation:foundation`<br>`androidx.compose.foundation:foundation-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Geometry | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.ui:ui-geometry` | https://github.com/JetBrains/compose-multiplatform |
| Compose Geometry | 1.11.2 | Apache-2.0 | `androidx.compose.ui:ui-geometry`<br>`androidx.compose.ui:ui-geometry-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Graphics | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.ui:ui-graphics` | https://github.com/JetBrains/compose-multiplatform |
| Compose Graphics | 1.11.2 | Apache-2.0 | `androidx.compose.ui:ui-graphics`<br>`androidx.compose.ui:ui-graphics-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Layouts | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.foundation:foundation-layout` | https://github.com/JetBrains/compose-multiplatform |
| Compose Layouts | 1.11.2 | Apache-2.0 | `androidx.compose.foundation:foundation-layout`<br>`androidx.compose.foundation:foundation-layout-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Material 3 Window Size Class | 1.4.0 | Apache-2.0 | `androidx.compose.material3:material3-window-size-class`<br>`androidx.compose.material3:material3-window-size-class-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Material 3 Window Size Class | 1.9.0 | Apache-2.0 | `org.jetbrains.compose.material3:material3-window-size-class` | https://github.com/JetBrains/compose-jb |
| Compose Runtime | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.runtime:runtime` | https://github.com/JetBrains/compose-multiplatform |
| Compose Runtime | 1.11.2 | Apache-2.0 | `androidx.compose.runtime:runtime`<br>`androidx.compose.runtime:runtime-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Runtime Annotation | 1.11.2 | Apache-2.0 | `androidx.compose.runtime:runtime-annotation`<br>`androidx.compose.runtime:runtime-annotation-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Runtime Retain | 1.11.2 | Apache-2.0 | `androidx.compose.runtime:runtime-retain`<br>`androidx.compose.runtime:runtime-retain-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Saveable | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.runtime:runtime-saveable` | https://github.com/JetBrains/compose-multiplatform |
| Compose Saveable | 1.11.2 | Apache-2.0 | `androidx.compose.runtime:runtime-saveable`<br>`androidx.compose.runtime:runtime-saveable-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose UI | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.ui:ui` | https://github.com/JetBrains/compose-multiplatform |
| Compose UI | 1.11.2 | Apache-2.0 | `androidx.compose.ui:ui`<br>`androidx.compose.ui:ui-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose UI Text | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.ui:ui-text` | https://github.com/JetBrains/compose-multiplatform |
| Compose UI Text | 1.11.2 | Apache-2.0 | `androidx.compose.ui:ui-text`<br>`androidx.compose.ui:ui-text-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Unit | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.ui:ui-unit` | https://github.com/JetBrains/compose-multiplatform |
| Compose Unit | 1.11.2 | Apache-2.0 | `androidx.compose.ui:ui-unit`<br>`androidx.compose.ui:ui-unit-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Compose Util | 1.11.1 | Apache-2.0 | `org.jetbrains.compose.ui:ui-util` | https://github.com/JetBrains/compose-multiplatform |
| Compose Util | 1.11.2 | Apache-2.0 | `androidx.compose.ui:ui-util`<br>`androidx.compose.ui:ui-util-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Core | 1.18.0 | Apache-2.0 | `androidx.core:core` | https://cs.android.com/androidx/platform/frameworks/support |
| Core Kotlin Extensions | 1.18.0 | Apache-2.0 | `androidx.core:core-ktx` | https://cs.android.com/androidx/platform/frameworks/support |
| coui-blur | 2.1.0 | Apache-2.0 | `io.github.suqi8.coui.kmp:coui-blur`<br>`io.github.suqi8.coui.kmp:coui-blur-android` | https://github.com/suqi8/coui |
| coui-core | 2.1.0 | Apache-2.0 | `io.github.suqi8.coui.kmp:coui-core`<br>`io.github.suqi8.coui.kmp:coui-core-android` | https://github.com/suqi8/coui |
| coui-icons | 2.1.0 | Apache-2.0 | `io.github.suqi8.coui.kmp:coui-icons`<br>`io.github.suqi8.coui.kmp:coui-icons-android` | https://github.com/suqi8/coui |
| coui-preference | 2.1.0 | Apache-2.0 | `io.github.suqi8.coui.kmp:coui-preference`<br>`io.github.suqi8.coui.kmp:coui-preference-android` | https://github.com/suqi8/coui |
| coui-shader | 2.1.0 | Apache-2.0 | `io.github.suqi8.coui.kmp:coui-shader`<br>`io.github.suqi8.coui.kmp:coui-shader-android` | https://github.com/suqi8/coui |
| coui-squircle | 2.1.0 | Apache-2.0 | `io.github.suqi8.coui.kmp:coui-squircle`<br>`io.github.suqi8.coui.kmp:coui-squircle-android` | https://github.com/suqi8/coui |
| coui-ui | 2.1.0 | Apache-2.0 | `io.github.suqi8.coui.kmp:coui-ui`<br>`io.github.suqi8.coui.kmp:coui-ui-android` | https://github.com/suqi8/coui |
| Emoji2 | 1.4.0 | Apache-2.0 | `androidx.emoji2:emoji2` | https://cs.android.com/androidx/platform/frameworks/support |
| error-prone annotations | 2.18.0 | Apache-2.0 | `com.google.errorprone:error_prone_annotations` | https://github.com/google/error-prone |
| Experimental annotation | 1.4.1 | Apache-2.0 | `androidx.annotation:annotation-experimental` | https://cs.android.com/androidx/platform/frameworks/support |
| FindBugs-jsr305 | 3.0.2 | Apache-2.0 | `com.google.code.findbugs:jsr305` | https://code.google.com/p/jsr-305/ |
| Guava InternalFutureFailureAccess and InternalFutures | 1.0.1 | Apache-2.0 | `com.google.guava:failureaccess` | https://github.com/google/guava |
| Guava ListenableFuture only | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | `com.google.guava:listenablefuture` | https://github.com/google/guava |
| Guava: Google Core Libraries for Java | 32.0.0-jre | Apache-2.0 | `com.google.guava:guava` | https://github.com/google/guava |
| Health Connect Client | 1.1.0 | Apache-2.0 | `androidx.health.connect:connect-client` | https://cs.android.com/androidx/platform/frameworks/support |
| Health Connect Client External Protobuf | 1.1.0 | BSD-3-Clause | `androidx.health.connect:connect-client-external-protobuf` | https://cs.android.com/androidx/platform/frameworks/support |
| Health Connect Client Proto | 1.1.0 | Apache-2.0 | `androidx.health.connect:connect-client-proto` | https://cs.android.com/androidx/platform/frameworks/support |
| J2ObjC Annotations | 2.8 | Apache-2.0 | `com.google.j2objc:j2objc-annotations` | https://github.com/google/j2objc |
| JetBrains Java Annotations | 23.0.0 | Apache-2.0 | `org.jetbrains:annotations` | https://github.com/JetBrains/java-annotations |
| JSpecify annotations | 1.0.0 | Apache-2.0 | `org.jspecify:jspecify` | https://github.com/jspecify/jspecify/ |
| Kotlin Stdlib | 2.4.21 | Apache-2.0 | `org.jetbrains.kotlin:kotlin-stdlib` | https://github.com/JetBrains/kotlin |
| kotlinx-coroutines-android | 1.11.0 | Apache-2.0 | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx-coroutines-core | 1.11.0 | Apache-2.0 | `org.jetbrains.kotlinx:kotlinx-coroutines-core`<br>`org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx-coroutines-guava | 1.11.0 | Apache-2.0 | `org.jetbrains.kotlinx:kotlinx-coroutines-guava` | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx-serialization-core | 1.13.0 | Apache-2.0 | `org.jetbrains.kotlinx:kotlinx-serialization-core`<br>`org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | https://github.com/Kotlin/kotlinx.serialization |
| kotlinx-serialization-json | 1.13.0 | Apache-2.0 | `org.jetbrains.kotlinx:kotlinx-serialization-json`<br>`org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | https://github.com/Kotlin/kotlinx.serialization |
| Lifecycle Kotlin Extensions | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-runtime-ktx`<br>`androidx.lifecycle:lifecycle-runtime-ktx-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle LiveData | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-livedata` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle LiveData Core | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-livedata-core` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle Process | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-process` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle Runtime | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-runtime`<br>`androidx.lifecycle:lifecycle-runtime-android`<br>`org.jetbrains.androidx.lifecycle:lifecycle-runtime`<br>`org.jetbrains.androidx.lifecycle:lifecycle-runtime-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle Runtime Compose | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-runtime-compose`<br>`androidx.lifecycle:lifecycle-runtime-compose-android`<br>`org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose`<br>`org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle ViewModel | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-viewmodel`<br>`androidx.lifecycle:lifecycle-viewmodel-android`<br>`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`<br>`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle ViewModel Compose | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-viewmodel-compose`<br>`androidx.lifecycle:lifecycle-viewmodel-compose-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle ViewModel Kotlin Extensions | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-viewmodel-ktx` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle ViewModel with SavedState | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-viewmodel-savedstate`<br>`androidx.lifecycle:lifecycle-viewmodel-savedstate-android`<br>`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate`<br>`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Lifecycle-Common | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-common`<br>`androidx.lifecycle:lifecycle-common-jvm`<br>`org.jetbrains.androidx.lifecycle:lifecycle-common`<br>`org.jetbrains.androidx.lifecycle:lifecycle-common-jvm` | https://cs.android.com/androidx/platform/frameworks/support |
| LiveData Core Kotlin Extensions | 2.11.0 | Apache-2.0 | `androidx.lifecycle:lifecycle-livedata-core-ktx` | https://cs.android.com/androidx/platform/frameworks/support |
| markdown | 0.7.18 | Apache-2.0 | `org.jetbrains:markdown`<br>`org.jetbrains:markdown-jvm` | https://github.com/JetBrains/markdown |
| MaterialKolor | 5.0.0 | MIT | `com.materialkolor:material-color-utilities`<br>`com.materialkolor:material-color-utilities-android` | https://github.com/jordond/materialkolor |
| Navigation Event | 1.2.0 | Apache-2.0 | `androidx.navigationevent:navigationevent`<br>`androidx.navigationevent:navigationevent-android` | https://cs.android.com/androidx/platform/frameworks/support |
| NavigationEvent Compose | 1.2.0 | Apache-2.0 | `androidx.navigationevent:navigationevent-compose`<br>`androidx.navigationevent:navigationevent-compose-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Poko Annotations | 0.23.1 | Apache-2.0 | `dev.drewhamilton.poko:poko-annotations`<br>`dev.drewhamilton.poko:poko-annotations-jvm` | https://github.com/drewhamilton/Poko/tree/main |
| Profile Installer | 1.4.0 | Apache-2.0 | `androidx.profileinstaller:profileinstaller` | https://cs.android.com/androidx/platform/frameworks/support |
| RaTeX Android | 0.1.16 | MIT | `io.github.erweixin:ratex-android` | https://github.com/erweixin/RaTeX |
| Saved State | 1.3.6 | Apache-2.0 | `org.jetbrains.androidx.savedstate:savedstate` | https://github.com/JetBrains/compose-jb |
| Saved State | 1.4.0 | Apache-2.0 | `androidx.savedstate:savedstate`<br>`androidx.savedstate:savedstate-android` | https://cs.android.com/androidx/platform/frameworks/support |
| Saved State Compose | 1.3.6 | Apache-2.0 | `org.jetbrains.androidx.savedstate:savedstate-compose` | https://github.com/JetBrains/compose-jb |
| Saved State Compose | 1.4.0 | Apache-2.0 | `androidx.savedstate:savedstate-compose`<br>`androidx.savedstate:savedstate-compose-android` | https://cs.android.com/androidx/platform/frameworks/support |
| SavedState Kotlin Extensions | 1.4.0 | Apache-2.0 | `androidx.savedstate:savedstate-ktx` | https://cs.android.com/androidx/platform/frameworks/support |
| Transition | 1.6.0 | Apache-2.0 | `androidx.transition:transition` | https://cs.android.com/androidx/platform/frameworks/support |
| VersionedParcelable | 1.1.1 | Apache-2.0 | `androidx.versionedparcelable:versionedparcelable` | http://source.android.com |
| WindowManager | 1.5.0 | Apache-2.0 | `androidx.window:window` | https://cs.android.com/androidx/platform/frameworks/support |
| WindowManager Core | 1.5.0 | Apache-2.0 | `androidx.window:window-core`<br>`androidx.window:window-core-android` | https://cs.android.com/androidx/platform/frameworks/support |

MIT、BSD、ISC 要求随包附上的版权行：

- Checker Qual：Copyright 2004-present by the Checker Framework developers
- Health Connect Client External Protobuf：Copyright 2008 Google Inc.
- MaterialKolor：Copyright (c) 2025 Jordon de Hoog
- RaTeX Android：Copyright (c) erweixin
