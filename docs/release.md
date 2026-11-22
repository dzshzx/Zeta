# 发版

> 本页说明怎样发版、版本号从哪里来、正式版怎么签名、Release 里有哪些文件、出错后怎么办，以及怎样把发出的版本装到手机上。

Zeta 的正式版（包名 `com.dzshzx.zeta`）只经 GitHub Release 发布。每个 Release 对应一个纯 `vX.Y.Z` tag，资产是
签好名的 APK、它的校验和，以及应用内检查更新读的 `update.json`。构建、签名和发布都在 GitHub Actions 里做，本机不构建
正式版、不打 tag。

## 发版

发版前先在主 checkout 上让测试版照正式版那样经 R8 构建，在模拟器上冷启动一次。合入的门禁（`AppStartTest`）只查不经 R8 的启动，
R8 误删了运行时才按类名找的类（见 CLAUDE.md「坑」）只有这里查得到：

```sh
ZETA_R8=1 ZETA_PHONE_OWNER=release tools/phone-run tools/phone-tests/smoke.sh
```

发版本身只有一条命令：手动触发 `.github/workflows/release.yml`。先 dry run，看过运行摘要再正式发：

```sh
gh workflow run release.yml --ref master -f dry_run=true
gh workflow run release.yml --ref master -f bump=patch
```

输入：

- `bump`：`patch`（默认）、`minor` 或 `major`。版本长期停在 0.x.x，平常只升 patch；minor 要维护者先同意，不发 1.0。
- `dry_run`：默认 `false`。设为 `true` 时完整构建、签名、核对、打包（任何分支都能跑，但 environment `release`
  只放 `master`，别的分支到签名就停），运行摘要列出将要发布的版本、
  源提交、各资产的 sha256 和 Release 说明，但不打 tag、不发布。资产留在这次运行的 `release` 构件里（保留 7 天）。
- `runner`：门禁（要跑时）和构建跑在哪，`self-hosted`（默认，维护者的 runner）或 `github-hosted`（`ubuntu-latest`，
  本机 runner 不在线时用）。签名、发布不受它影响，总在 GitHub 托管机上。

工作流依次：

1. **version**：`tools/release next-version`。正式发版（非 dry run）只在默认分支上跑。这个提交上有纯 `vX.Y.Z` tag、
   且同名 Release 已发布，就是发过版，非 dry run 直接失败；只有 tag、Release 没发布，就是上次没发完，沿用这个版本
   续发，不再升档。否则取仓里最高的纯 `vX.Y.Z` tag 按 `bump` 升一档。同时算出 versionCode，并查这个提交是否已过全量门禁：
   本仓里手动或定时触发的 `ci.yml`、`release.yml` 在它上面有过成功的运行。
2. **gate**：调 `ci.yml` 跑全量门禁 `ZETA_GATE_FULL=1 bash scripts/check.sh`。合入时的门禁不含截图测试，发版前在这里
   一个不落地跑。这个提交已过全量门禁（上一步查到了）就跳过：先 dry run 再正式发时，全量门禁只在 dry run 里跑一次。
3. **build**：在维护者的 runner 上（`runner=github-hosted` 时在托管的 ubuntu 机上）构建 `:app:assembleRelease`，出未签名的 `Zeta-vX.Y.Z.apk`。构建前在
   这份检出里给本提交打上本地 tag，版本名因此就是干净的 `X.Y.Z`（见下节）。
4. **sign**：在 environment `release` 里取正式发版密钥，`tools/release sign` 签名，删掉密钥库；`tools/release verify`
   核对；`tools/release package` 写出资产并写运行摘要。构建（会跑 Gradle 插件和第三方依赖的代码）和碰密钥分在两个
   job，构建那一边拿不到 Secrets。
5. **publish**（非 dry run）：`tools/release publish` 删掉同名的旧草稿，建含全部资产的草稿，经 GitHub API 在本提交上
   打附注 tag `vX.Y.Z`（已指向本提交就跳过，指向别的提交就失败），最后发布草稿并设为最新。只有这个 job 能写仓库。

## 版本号

版本照 SemVer 从 git 推出来，写在 `app/build.gradle.kts` 开头，不手写：

- versionCode 是 HEAD 的提交数，每合入一次自动加一。
- 提交上有纯 `vX.Y.Z` tag 时，versionName 就是 `X.Y.Z`；别的提交是下一个版本的预发布 `X.Y.(Z+1)-dev.N+gHASH`。
- release.yml 构建前在检出里 `git tag vX.Y.Z`（只是本地的，不推送），所以 CI 构建出的正式版是 `X.Y.Z`；tag 由
  publish 那一步经 API 打到 GitHub 上。所有 CI 检出都用 `fetch-depth: 0`，否则提交数和 tag 都不对。
- 已发布的 tag 不移动、不删除、不复用；发布后才发现问题时向前修，修好后发下一个 patch。

## 签名

正式版用正式发版密钥签名，只签 APK 签名方案 v3（关掉 v1、v2、v4），并带上签名 lineage `release/lineage.bin`。
v0.7.0 及更早的正式版是用调试密钥（证书 `CN=Android Debug`）签的；lineage 是旧证书给新证书的签名，系统据此认定
新证书接替旧证书，所以已装的旧版直接 `pm install -r` 覆盖升级，不用卸载，数据都在。

- **只签 v3**：minSdk 是 35，所有支持的系统都认 v3。v1、v2 不支持密钥轮换，开着它们时 apksigner 要用 lineage 里
  最老的那把私钥签它们，每次发版都得拿出旧私钥。只签 v3 时每次签名只要新密钥，旧私钥只在生成 lineage 时用一次。
- **v3.0 块**：apksigner 默认把轮换放到 Android 13（API 33）起才认的 v3.1 块，v3.0 块仍用旧密钥签；minSdk 35 不低于
  33，apksigner 直接把新证书和 lineage 写进 v3.0 块，不出 v3.1 块。
- **旧证书作废**：lineage 默认不给旧证书「回滚」能力，装过新签名的版本以后，只用旧证书签的包再也装不上来。
- AGP 的签名配置不支持 lineage，所以 release 构建出的是未签名包，由 `tools/release sign` 调 apksigner 签。

`tools/release sign` 实际执行的命令（口令按 `env:<变量名>` 交给 apksigner，不进命令行）：

```sh
apksigner sign --ks <密钥库> --ks-key-alias <别名> \
  --ks-pass env:ZETA_RELEASE_STORE_PASSWORD [--key-pass env:ZETA_RELEASE_KEY_PASSWORD] \
  --lineage release/lineage.bin \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false \
  --out Zeta-vX.Y.Z.apk <未签名的包>
```

签名前它核对 `release/lineage.bin` 里的证书与 `release/signing-certs.txt` 逐行相同；签完和 `verify`、`package`、
`check-download` 一样核对：apksigner 验得过、只有 v3、签名证书是 `release/signing-certs.txt` 的最后一行、包里的
lineage 与登记的逐行相同、`zipalign -c -P 16 4` 通过（未压缩的 `.so` 按 16 KB 对齐），以及包名、versionName、
versionCode。

测试版（`com.dzshzx.zeta.dev`）和 debug 包仍用调试密钥签名，不参与发版。

### 轮换实验

上面的做法先在模拟器（Android 15，x86_64）上用两把临时 RSA 密钥和一个只有清单、包名与 Zeta 不同的最小 APK
（minSdk 35）验过：

| 装上的 | 再装的 | 结果 |
| --- | --- | --- |
| 旧密钥签、只有 v2（同 AGP 的默认） | 新密钥加 lineage、只有 v3 | `Success`，数据目录里的文件还在 |
| 同上 | 新密钥、没有 lineage | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| 新密钥加 lineage 的版本 | 下一版，仍是新密钥加 lineage（签名时没给旧密钥） | `Success` |
| 新密钥加 lineage 的版本 | 旧密钥签的更高版本 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |

带 lineage 而开着 v2（或 v1）时，只给新密钥 apksigner 直接报错 `v2 signing enabled but the oldest signer in the
SigningCertificateLineage is missing`。

## 生成正式发版密钥

只做一次，由维护者在自己的机器上做。旧私钥（调试密钥）只在第 2 步用到。工作目录用完删掉，口令不进 shell 历史。

```sh
umask 077
work=$(mktemp -d)
read -rs ZETA_RELEASE_STORE_PASSWORD && export ZETA_RELEASE_STORE_PASSWORD   # 新口令
read -rs OLD_PASS && export OLD_PASS                                         # 调试密钥库的口令
bt=$ANDROID_HOME/build-tools/37.0.0                                          # app/build.gradle.kts 的 buildToolsVersion

# 1. 新密钥：RSA 4096，有效期 50 年。PKCS12 的密钥口令就是密钥库口令
keytool -genkeypair -keystore "$work/zeta-release.p12" -storetype PKCS12 -storepass:env ZETA_RELEASE_STORE_PASSWORD \
  -alias zeta-release -keyalg RSA -keysize 4096 -validity 18250 -dname "CN=Zeta"

# 2. lineage：旧证书 → 新证书
"$bt/apksigner" rotate --out release/lineage.bin \
  --old-signer --ks <调试密钥库> --ks-key-alias <调试密钥的别名> --ks-pass env:OLD_PASS \
  --new-signer --ks "$work/zeta-release.p12" --ks-key-alias zeta-release --ks-pass env:ZETA_RELEASE_STORE_PASSWORD

# 3. 登记证书：打印出的第一行应与 release/signing-certs.txt 已登记的调试证书相同，把第二行追加到文件末尾
"$bt/apksigner" lineage --in release/lineage.bin --print-certs | sed -n 's/.*certificate SHA-256 digest: //p'
```

4. 本地试签一次：在一份临时克隆里打上本地 tag、构建，用新密钥签名并核对（`-version-code` 是那个提交的
   `git rev-list --count HEAD`），确认通过后提交 `release/lineage.bin` 和 `release/signing-certs.txt`：

   ```sh
   go -C tools/release run . sign -apk <克隆>/app/build/outputs/apk/release/Zeta-vX.Y.Z.apk \
     -keystore "$work/zeta-release.p12" -alias zeta-release -out "$work/Zeta-vX.Y.Z.apk"
   go -C tools/release run . verify -apk "$work/Zeta-vX.Y.Z.apk" -version X.Y.Z -version-code N
   ```

5. 在 GitHub 上建 environment `release`，部署分支只放 `master`，再设四个 Secrets（见下节）：

   ```sh
   gh api -X PUT repos/dzshzx/Zeta/environments/release --input - <<'EOF'
   {"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
   EOF
   gh api -X POST repos/dzshzx/Zeta/environments/release/deployment-branch-policies -f name=master -f type=branch
   base64 -w0 "$work/zeta-release.p12" | gh secret set ZETA_RELEASE_KEYSTORE_BASE64 --env release --repo dzshzx/Zeta
   printf %s zeta-release | gh secret set ZETA_RELEASE_KEY_ALIAS --env release --repo dzshzx/Zeta
   printf %s "$ZETA_RELEASE_STORE_PASSWORD" | gh secret set ZETA_RELEASE_STORE_PASSWORD --env release --repo dzshzx/Zeta
   printf %s "$ZETA_RELEASE_STORE_PASSWORD" | gh secret set ZETA_RELEASE_KEY_PASSWORD --env release --repo dzshzx/Zeta
   ```

6. 把 `zeta-release.p12` 和口令存进维护者的密码管理器，再 `rm -rf "$work"`。密钥丢了就再也发不出能覆盖升级的版本。

以后要再换密钥时，用 `apksigner rotate --in release/lineage.bin` 在现有 lineage 后面接上新证书，同样追加到
`release/signing-certs.txt`。

## GitHub 上的设置

- environment `release`：只有 sign 那个 job 用它；部署分支只放 `master`，所以 dry run 也要在 `master` 上跑才能签名。
- 它的 Secrets：

  | 名字 | 内容 |
  | --- | --- |
  | `ZETA_RELEASE_KEYSTORE_BASE64` | 密钥库文件（PKCS12）的 base64 |
  | `ZETA_RELEASE_KEY_ALIAS` | 密钥库里正式发版密钥的别名 |
  | `ZETA_RELEASE_STORE_PASSWORD` | 密钥库的口令 |
  | `ZETA_RELEASE_KEY_PASSWORD` | 密钥的口令（PKCS12 时与密钥库口令相同） |

- 工作流的权限都写在 YAML 里：默认只读，version 多一个读 commit status，只有 publish 能写仓库内容。
- `ci.yml` 默认跑在带 `self-hosted`、`nerv` 标签的 runner 上，每个 job 用新的 HOME；runner 的 `.env` 给
  `ANDROID_HOME`（已接受 SDK 许可）和持久的 `GRADLE_USER_HOME`。JDK 25 和 Go 由 `actions/setup-java`、
  `actions/setup-go` 装。工作流不设 `pull_request` 触发，公开仓的 PR 不会在这台 runner 上跑代码。

## Release 的资产

| 文件 | 内容 |
| --- | --- |
| `Zeta-vX.Y.Z.apk` | 签好名的正式版，只有 arm64-v8a 一个包 |
| `Zeta-vX.Y.Z.apk.sha256` | `sha256sum` 的格式：`<sha256>  Zeta-vX.Y.Z.apk` |
| `update.json` | 应用内检查更新读的，格式见下 |
| `Zeta-vX.Y.Z.msi` | Windows 桌面版的安装包（`desktop/windows/package.sh` 打的，未做代码签名），按用户装到 `%LOCALAPPDATA%\Programs\Zeta` |
| `Zeta-vX.Y.Z.msi.sha256` | 同上的格式 |

`tools/install.sh --release` 和 `check-download` 只取、只核对手机的前三个。

`update.json` 是一个 JSON 对象，最新版的固定地址是
`https://github.com/dzshzx/Zeta/releases/latest/download/update.json`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `versionCode` | 整数 | 包的 versionCode，比已装的大才是新版本 |
| `versionName` | 字符串 | `X.Y.Z` |
| `tag` | 字符串 | `vX.Y.Z` |
| `publishedAt` | 字符串 | 打包的时间，UTC，RFC 3339（`2026-11-21T08:00:00Z`） |
| `notes` | 字符串 | 更新说明：上一个正式版以来的提交标题，Markdown 列表，旧的在前 |
| `apk` | 字符串 | APK 的文件名 `Zeta-vX.Y.Z.apk` |
| `url` | 字符串 | APK 的下载地址 `https://github.com/dzshzx/Zeta/releases/download/vX.Y.Z/Zeta-vX.Y.Z.apk` |
| `size` | 整数 | APK 的字节数 |
| `sha256` | 字符串 | APK 的 SHA-256，小写十六进制 |
| `certificateSha256` | 字符串 | 签名证书的 SHA-256，小写十六进制 |
| `sourceCommit` | 字符串 | 构建所用的完整提交号 |

应用读它的哪几项、下载后怎么核对见下节。以后加字段只加不改，读的一方不认识的字段就忽略。

Release 的说明写明版本、versionCode、`source_commit` 和签名证书，下面是与 `notes` 相同的提交标题。

## 应用内更新

正式版自己检查、下载和安装新版本，代码在 `app/src/main/kotlin/com/dzshzx/zeta/core/update/`，界面在
`ui/settings/UpdateScreens.kt`。

- **谁查**：只有正式发版证书签的包查。构建时把 `release/signing-certs.txt` 的最后一行写进
  `BuildConfig.RELEASE_CERT_SHA256`，本包现在的签名证书就是它才查；测试版、debug 包和别人自己编的包不查，
  「关于」里只显示版本。
- **什么时候查**：设置「关于」里点「版本」那一行；应用回到前台时每天最多自动查一次（「关于」里的「自动检查更新」，
  默认开）。自动查到新版本时发一条通知（渠道「版本更新」，同一个版本只发一次），点开到「版本更新」页。不常驻、
  不定时唤醒。
- **从哪取**：清单是上面那个固定地址，APK 按清单的 `url`。GitHub 上的地址先直连，连不上、超时、HTTP 出错或内容
  不对时走 `https://gh-proxy.com/<原地址>`，和 `assets/zeta_setup_debian.sh` 下载 GitHub 文件一样。versionCode
  比已装的大才算新版本。
- **核对**：APK 的大小和 sha256 对清单；`getPackageArchiveInfo` 读出的包名是本包、versionCode 等于清单、签名
  lineage 里含已装的这个包现在的证书（同一张证书，或从它轮换过来的；所以调试证书签的 v0.7.0 及更早也能升上来）。
  哪一项不对都删掉文件、说原因。gh-proxy 是第三方的中转，清单和 APK 都可能被换掉，所以最后靠签名把关，装的时候
  系统还会再核对一次。
- **安装**：装包会结束本应用的界面，进行中的对话照样跑完，正在执行的手机工具调用却会失败，所以只等正在执行的
  那一次调用结束再装；对话正在回答、后台屏幕开着都不等。有 root 时经 su 用 `pm install -r`（APK 经管道交给 `-S`，同 `tools/install.sh`），装好在原来
  那块屏上重新打开 Zeta；没有 root 时交给 PackageInstaller 会话，由用户在系统的安装界面上确认，要先在系统设置里
  允许 Zeta「安装未知应用」。

在模拟器上试：测试版有两项调试覆盖，`updateDevManifest` 换清单地址，`updateDevCert` 换信任的证书（设成调试证书，
即 `release/signing-certs.txt` 的第一行，测试版也查）。只有测试版读它们（`BuildConfig.UPDATE_DEV_OVERRIDE`），
正式版里读覆盖的分支被 R8 去掉。在工作站上起一个 HTTP 服务器，放 `update.json` 和一个调试密钥签名、versionCode
更大的测试版 APK（比如在临时克隆里加几个空提交再 `assembleDebug`），`url` 指向它；模拟器经 10.0.2.2 访问宿主。
设备脚本里：

```sh
vd prefs-set updateDevManifest=http://10.0.2.2:<端口>/update.json updateDevCert=<调试证书的 SHA-256>
vd launch
android "am start --display $DISPLAY_ID -n $PKG/com.dzshzx.zeta.ui.MainActivity --es com.dzshzx.zeta.extra.SETTINGS_PAGE Update"
```

打开的是「版本更新」页，点「更新」走一遍下载、核对、经 root 安装。收尾的 `vd stop` 把两项覆盖改回去。

## 出错后怎么办

- 任何一步失败，修好后用同一条触发命令重跑。打 tag 之前失败时，重跑按同一个 `bump` 算出同一个版本，并替换上次
  留下的草稿；打 tag 之后失败时，重跑认出这个提交上的 tag，沿用这个版本继续发布。不用手工删 tag、删草稿或发布草稿。
- 签名一步报 lineage 或证书对不上：`release/lineage.bin`、`release/signing-certs.txt` 和 Secrets 里的密钥不是同一套，
  按上面「生成正式发版密钥」核对。

## 装到手机上（上线）

```sh
ZETA_PHONE_OWNER=main tools/install.sh --release          # 最新的 Release
ZETA_PHONE_OWNER=main tools/install.sh --release v0.7.1   # 指定版本
```

正式版是日常在用的那一份，脚本只在命令前写明 `ZETA_PHONE_OWNER=main` 时才装；没设时不装，免得没设占用者名字的测试流程碰到它。

它用 `gh release download` 取 APK、`.sha256` 和 `update.json`，经 `tools/release check-download` 核对 sha256、
签名证书（本仓 `release/signing-certs.txt` 的最后一行）、lineage、包名和版本，再按 `pm install -r` 装到手机。
要 `gh`、`go` 和 Android SDK 的 build-tools。

## 自己核对一个 Release

```sh
gh release download vX.Y.Z --repo dzshzx/Zeta --dir <空目录> \
  --pattern 'Zeta-v*.apk' --pattern 'Zeta-v*.apk.sha256' --pattern update.json
go -C tools/release run . check-download -dir <那个目录>
```

不用本仓的工具时：`sha256sum -c Zeta-vX.Y.Z.apk.sha256`，再用 `apksigner verify --print-certs Zeta-vX.Y.Z.apk`
看签名证书的 SHA-256 是否是 `release/signing-certs.txt` 的最后一行。
