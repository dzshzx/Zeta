package main

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const (
	oldCert = "72fd950d6fc47e2d03beeb6d51e59aed510bfe637f3ff2c8d23f96761cfbe4f3"
	newCert = "e28947354c6bc34e0e30620fe17687744a8ddcb3a08ef9f5150f52ee987212ff"
	// 与证书无关的公钥摘要：不能被当成证书读出来
	publicKey = "d471617fb254631b77b088195c45684905f00d72a1331b0f51d5118ca49c2235"
)

// apksigner verify -v --print-certs 对「新密钥加 lineage、只签 v3」的包的输出（取自 docs/release.md 的轮换实验）
const verifyRotated = `Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v3.2 scheme (APK Signature Scheme v3.2): false
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
V3.0 Signer: certificate DN: CN=Rotation Probe New
V3.0 Signer: certificate SHA-256 digest: ` + newCert + `
V3.0 Signer: certificate SHA-1 digest: d216aab1a2587c23288af47fe6f1b9eccdb843c4
V3.0 Signer: key algorithm: RSA
V3.0 Signer: public key SHA-256 digest: ` + publicKey + `
`

// 同一条命令对 AGP 用调试密钥签的包（只有 v2）的输出
const verifyDebugV2 = `Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Verified using v4 scheme (APK Signature Scheme v4): false
Number of signers: 1
Signer #1 certificate DN: CN=Android Debug, O=Android, C=US
Signer #1 certificate SHA-256 digest: ` + oldCert + `
Signer #1 public key SHA-256 digest: ` + publicKey + `
`

// apksigner lineage --in <包或 lineage 文件> --print-certs 的输出
const lineageOut = `Signer #1 in lineage certificate DN: CN=Android Debug, O=Android, C=US
Signer #1 in lineage certificate SHA-256 digest: ` + oldCert + `
Signer #1 in lineage certificate SHA-1 digest: 294e0972520273052f1103832a62a755d84d7ddf
Has installed data capability: true
Has rollback capability      : false
Signer #2 in lineage certificate DN: CN=Rotation Probe New
Signer #2 in lineage certificate SHA-256 digest: ` + newCert + `
Has installed data capability: true
`

func badging(pkg, code, name string) string {
	return "package: name='" + pkg + "' versionCode='" + code + "' versionName='" + name +
		"' platformBuildVersionName='17' platformBuildVersionCode='37'\nminSdkVersion:'35'\n"
}

// fakeCommands 替换掉 command：调用记成「程序名 参数…」，按最长的前缀找 answers 里的回答（随时读，测试中途改了也算），
// 没有就回空。回答以 "!" 开头时当作失败。
func fakeCommands(t *testing.T, answers map[string]string) *[]string {
	t.Helper()
	return fakeWith(t, func(call string) string { return answers[longestPrefix(call, answers)] })
}

// fakeSequence 同 fakeCommands，同一前缀的调用依次给 answers 里的回答，用完了一直给最后一个。
func fakeSequence(t *testing.T, answers map[string][]string) *[]string {
	t.Helper()
	asked := map[string]int{}
	return fakeWith(t, func(call string) string {
		key := longestPrefix(call, answers)
		list := answers[key]
		if len(list) == 0 {
			return ""
		}
		defer func() { asked[key]++ }()
		return list[min(asked[key], len(list)-1)]
	})
}

func fakeWith(t *testing.T, answer func(call string) string) *[]string {
	t.Helper()
	saved := command
	t.Cleanup(func() { command = saved })
	var calls []string
	command = func(name string, args ...string) (string, error) {
		call := filepath.Base(name) + " " + strings.Join(args, " ")
		calls = append(calls, call)
		out := answer(call)
		if msg, failed := strings.CutPrefix(out, "!"); failed {
			return "", errors.New(msg)
		}
		return out, nil
	}
	return &calls
}

// longestPrefix 是 answers 的键里作为 call 前缀最长的那个，没有就是空串。
func longestPrefix[V any](call string, answers map[string]V) string {
	best := ""
	for key := range answers {
		if strings.HasPrefix(call, key) && len(key) > len(best) {
			best = key
		}
	}
	return best
}

// fakeRepo 在临时目录里摆一个最小的仓（tools/release/go.mod、app/build.gradle.kts、登记的证书、lineage）
// 和带 apksigner 的 SDK，并把当前目录切到仓里的 tools/release。返回仓根。
func fakeRepo(t *testing.T, certs string) string {
	t.Helper()
	root := t.TempDir()
	sdk := t.TempDir()
	files := map[string]string{
		filepath.Join(root, "tools/release/go.mod"):           "module x\n",
		filepath.Join(root, "app/build.gradle.kts"):           "android {\n    buildToolsVersion = \"37.0.0\"\n}\n",
		filepath.Join(root, certsFile):                        certs,
		filepath.Join(root, lineageFile):                      "lineage",
		filepath.Join(sdk, "build-tools/37.0.0/apksigner"):    "",
		filepath.Join(root, "app/build/outputs/unsigned.apk"): "apk",
	}
	for path, text := range files {
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	t.Setenv("ANDROID_HOME", sdk)
	t.Setenv("JAVA_HOME", filepath.Join(sdk, "jdk"))
	t.Chdir(filepath.Join(root, "tools/release"))
	return root
}

// captureStdout 跑 f，返回它写到标准输出的内容；f 失败时测试失败。
func captureStdout(t *testing.T, f func() error) string {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	saved := os.Stdout
	os.Stdout = w
	ferr := f()
	os.Stdout = saved
	w.Close()
	data, err := io.ReadAll(r)
	if err != nil {
		t.Fatal(err)
	}
	if ferr != nil {
		t.Fatal(ferr)
	}
	return string(data)
}
