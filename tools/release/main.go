// release：Zeta 的发版工具，由 .github/workflows/release.yml 调用（发版经 fleet-state 的 rollout release Zeta 派发它），做法与出错后怎么办见 docs/release.md。
// 在仓里任何目录下运行都行：它往上找仓根（有 tools/release/go.mod 的那一层），读 release/ 下登记的签名证书与 lineage。
//
//	next-version -bump patch|minor|major -branch <默认分支> [-dry-run]
//	    这次发版的版本：最高的纯 vX.Y.Z tag 按 bump 升一档；这个提交已发过版就失败，有 tag 没发布就续发。
//	    同时给出 versionCode，按 $GITHUB_OUTPUT 的格式打印
//	sign -apk <未签名的包> -keystore <密钥库> -alias <别名> -out <签好的包>
//	    用正式发版密钥加 release/lineage.bin 只签 APK 签名方案 v3，签完核对签名
//	verify -apk <包> -version X.Y.Z -version-code N
//	    核对签名、证书、对齐、包名、版本名和 versionCode
//	package -apk <签好的包> -msi <桌面版安装包> -version X.Y.Z -version-code N -commit <提交> -repo <owner/name> -out <空目录>
//	    核对后写出 Release 的全部资产：APK、桌面版的 MSI、各自的 .sha256 和应用内更新用的 update.json，并写运行摘要
//	publish -version X.Y.Z -assets <package 的输出目录>
//	    删同名旧草稿、建含全部资产的草稿、经 API 在本提交上打附注 tag、发布并设为最新；失败后重跑能续上
//	check-download -dir <目录>
//	    核对 gh release download 下来的手机那几个资产（tools/install.sh --release 用），打印 APK 的路径
//
// apksigner、aapt2、zipalign 取 $ANDROID_HOME/build-tools/<app/build.gradle.kts 的 buildToolsVersion>；
// JAVA_HOME 的 bin 放到子进程 PATH 的最前（apksigner 用 PATH 上的 java）；没设时照 tools/java-home 找。
// 签名口令只经环境变量 ZETA_RELEASE_STORE_PASSWORD、ZETA_RELEASE_KEY_PASSWORD 交给 apksigner，不进命令行。
package main

import (
	"errors"
	"flag"
	"fmt"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	commands := map[string]func([]string) error{
		"next-version":   runNextVersion,
		"sign":           runSign,
		"verify":         runVerify,
		"package":        runPackage,
		"publish":        runPublish,
		"check-download": runCheckDownload,
	}
	command, ok := commands[os.Args[1]]
	if !ok {
		usage()
	}
	if err := command(os.Args[2:]); err != nil {
		fmt.Fprintln(os.Stderr, "release:", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: release next-version|sign|verify|package|publish|check-download [flags]")
	os.Exit(2)
}

// parse 解析子命令的参数，列出的几项必须给。
func parse(fs *flag.FlagSet, args []string, required ...string) error {
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() > 0 {
		return fmt.Errorf("多余的参数 %q", fs.Args())
	}
	for _, name := range required {
		if f := fs.Lookup(name); f == nil || f.Value.String() == "" {
			return errors.New("缺少 -" + name)
		}
	}
	return nil
}
