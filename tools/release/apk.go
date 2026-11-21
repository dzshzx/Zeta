package main

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

const (
	packageName = "com.dzshzx.zeta"
	// 签名口令的环境变量：apksigner 按 env:<名字> 自己去读，口令不出现在命令行里
	storePassEnv = "ZETA_RELEASE_STORE_PASSWORD"
	keyPassEnv   = "ZETA_RELEASE_KEY_PASSWORD"
	// 登记的签名证书（从旧到新，最后一把是现在签名用的正式证书）与对应的 lineage，都在仓里
	certsFile   = "release/signing-certs.txt"
	lineageFile = "release/lineage.bin"
)

var (
	buildToolsLine = regexp.MustCompile(`(?m)^\s*buildToolsVersion = "([0-9.]+)"`)
	certDigestLine = regexp.MustCompile(`(?m)certificate SHA-256 digest: ([0-9a-f]{64})\s*$`)
	schemeLine     = regexp.MustCompile(`(?m)^Verified using (v[0-9.]+) scheme \(.*\): (true|false)\s*$`)
	badgingLine    = regexp.MustCompile(`(?m)^package: name='([^']*)' versionCode='([0-9]+)' versionName='([^']*)'`)
	certHex        = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

// buildTools 是 Android SDK 里 build-tools 的一个版本目录。
type buildTools string

func (b buildTools) tool(name string) string { return filepath.Join(string(b), name) }

// findBuildTools 取 app/build.gradle.kts 写的 buildToolsVersion：AGP 构建时用、缺了会自己下载的就是这一版。
// SDK 的位置与 tools/gradle 相同：ANDROID_HOME，默认 ~/Android/Sdk。
func findBuildTools(root string) (buildTools, error) {
	gradle, err := os.ReadFile(filepath.Join(root, "app", "build.gradle.kts"))
	if err != nil {
		return "", err
	}
	m := buildToolsLine.FindSubmatch(gradle)
	if m == nil {
		return "", errors.New("app/build.gradle.kts 里没有 buildToolsVersion")
	}
	sdk := os.Getenv("ANDROID_HOME")
	if sdk == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		sdk = filepath.Join(home, "Android", "Sdk")
	}
	dir := buildTools(filepath.Join(sdk, "build-tools", string(m[1])))
	if _, err := os.Stat(dir.tool("apksigner")); err != nil {
		return "", fmt.Errorf("%s 里没有 apksigner：先 sdkmanager --install \"build-tools;%s\"", dir, m[1])
	}
	return dir, nil
}

// parseCerts 读 release/signing-certs.txt：# 起注释，其余每行一个证书 SHA-256（小写十六进制），从旧到新。
// 至少两把：0.7.x 及以前的正式版用的调试证书，和换上的正式发版证书。
func parseCerts(text string) ([]string, error) {
	var certs []string
	for i, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if !certHex.MatchString(line) {
			return nil, fmt.Errorf("%s 第 %d 行不是证书的 SHA-256：%q", certsFile, i+1, line)
		}
		certs = append(certs, line)
	}
	if len(certs) < 2 {
		return nil, fmt.Errorf("%s 只登记了 %d 把证书：正式发版证书还没登记，生成步骤见 docs/release.md「生成正式发版密钥」", certsFile, len(certs))
	}
	return certs, nil
}

func registeredCerts(root string) ([]string, error) {
	text, err := os.ReadFile(filepath.Join(root, certsFile))
	if err != nil {
		return nil, err
	}
	return parseCerts(string(text))
}

// certDigests 取 apksigner 输出里每一行的证书 SHA-256，按出现的顺序。
func certDigests(out string) []string {
	var digests []string
	for _, m := range certDigestLine.FindAllStringSubmatch(out, -1) {
		digests = append(digests, m[1])
	}
	return digests
}

// apkFacts 是从包里读出来的、核对要看的事实。
type apkFacts struct {
	Package     string
	VersionName string
	VersionCode int
	Schemes     map[string]bool // apksigner verify 认过的签名方案：v1、v2、v3、v3.1……
	Signers     []string        // 签名证书的 SHA-256，去重
	Lineage     []string        // 包里签名 lineage 的证书，从旧到新；没有 lineage 时为空
	Aligned     bool            // zipalign -c -P 16 4：未压缩的条目 4 字节、.so 16 KB 对齐
}

// parseVerify 读 apksigner verify -v --print-certs 的输出。
func parseVerify(out string) (schemes map[string]bool, signers []string) {
	schemes = map[string]bool{}
	for _, m := range schemeLine.FindAllStringSubmatch(out, -1) {
		schemes[m[1]] = m[2] == "true"
	}
	for _, d := range certDigests(out) {
		if !slices.Contains(signers, d) {
			signers = append(signers, d)
		}
	}
	return schemes, signers
}

// parseBadging 读 aapt2 dump badging 的第一行：包名、versionCode、versionName。
func parseBadging(out string) (pkg, name string, code int, err error) {
	m := badgingLine.FindStringSubmatch(out)
	if m == nil {
		return "", "", 0, errors.New("aapt2 dump badging 的输出里没有 package 一行")
	}
	code, err = strconv.Atoi(m[2])
	return m[1], m[3], code, err
}

// inspect 用 build-tools 读包：签名（apksigner 验不过就失败）、lineage、清单里的版本、对齐。
func inspect(bt buildTools, apk string) (apkFacts, error) {
	var f apkFacts
	verified, err := command(bt.tool("apksigner"), "verify", "-v", "--print-certs", apk)
	if err != nil {
		return f, fmt.Errorf("%s 的签名验不过：%w", filepath.Base(apk), err)
	}
	f.Schemes, f.Signers = parseVerify(verified)
	if lineage, err := command(bt.tool("apksigner"), "lineage", "--in", apk, "--print-certs"); err == nil {
		f.Lineage = certDigests(lineage)
	}
	badging, err := command(bt.tool("aapt2"), "dump", "badging", apk)
	if err != nil {
		return f, err
	}
	if f.Package, f.VersionName, f.VersionCode, err = parseBadging(badging); err != nil {
		return f, err
	}
	_, err = command(bt.tool("zipalign"), "-c", "-P", "16", "4", apk)
	f.Aligned = err == nil
	return f, nil
}

// checkSignature 核对签名：只有 v3（v1、v2 不支持轮换，开着它们每次签名都要旧私钥），签名证书是登记的最后一把，
// 包里的 lineage 与登记的证书逐个相同（这样用旧证书签的已装版本能直接覆盖升级），.so 按 16 KB 对齐。
func checkSignature(f apkFacts, certs []string) error {
	var problems []error
	if !f.Schemes["v3"] {
		problems = append(problems, errors.New("没有 APK 签名方案 v3 的签名"))
	}
	for _, scheme := range []string{"v1", "v2"} {
		if f.Schemes[scheme] {
			problems = append(problems, fmt.Errorf("带着 %s 签名：正式版只签 v3", scheme))
		}
	}
	if want := certs[len(certs)-1]; !slices.Equal(f.Signers, []string{want}) {
		problems = append(problems, fmt.Errorf("签名证书是 %v，不是 %s 登记的正式证书 %s", f.Signers, certsFile, want))
	}
	if !slices.Equal(f.Lineage, certs) {
		problems = append(problems, fmt.Errorf("包里的签名 lineage 是 %v，不是 %s 登记的 %v：用旧证书签的已装版本没法覆盖升级", f.Lineage, certsFile, certs))
	}
	if !f.Aligned {
		problems = append(problems, errors.New("zipalign -c -P 16 4 不过：未压缩条目或 .so 没对齐"))
	}
	return errors.Join(problems...)
}

// checkRelease 在签名之外再核对包名、版本名和 versionCode。
func checkRelease(f apkFacts, certs []string, version string, code int) error {
	var problems []error
	if f.Package != packageName {
		problems = append(problems, fmt.Errorf("包名是 %s，不是 %s", f.Package, packageName))
	}
	if f.VersionName != version {
		problems = append(problems, fmt.Errorf("versionName 是 %s，不是 %s", f.VersionName, version))
	}
	if f.VersionCode != code {
		problems = append(problems, fmt.Errorf("versionCode 是 %d，不是 %d", f.VersionCode, code))
	}
	if err := checkSignature(f, certs); err != nil {
		problems = append(problems, err)
	}
	return errors.Join(problems...)
}

// signingSetup 是签名和核对都要的：仓根、build-tools、登记的证书。
func signingSetup() (root string, bt buildTools, certs []string, err error) {
	if root, err = repoRoot(); err != nil {
		return
	}
	if bt, err = findBuildTools(root); err != nil {
		return
	}
	certs, err = registeredCerts(root)
	return
}

// signArgs 是 apksigner sign 的参数：正式发版密钥加 lineage，只签 v3。口令都按 env:<名字> 给。
func signArgs(apk, keystore, alias, lineage, out string, keyPass bool) []string {
	args := []string{"sign", "--ks", keystore, "--ks-key-alias", alias, "--ks-pass", "env:" + storePassEnv}
	if keyPass {
		args = append(args, "--key-pass", "env:"+keyPassEnv)
	}
	return append(args, "--lineage", lineage,
		"--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
		"--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
		"--out", out, apk)
}

// runSign 先核对 release/lineage.bin 与登记的证书一致，再签名，签完读回来核对签名。
func runSign(args []string) error {
	fs := flag.NewFlagSet("sign", flag.ContinueOnError)
	apk := fs.String("apk", "", "release 构建出的未签名包")
	keystore := fs.String("keystore", "", "正式发版密钥的密钥库")
	alias := fs.String("alias", "", "密钥库里正式发版密钥的别名")
	out := fs.String("out", "", "签好的包写到哪")
	if err := parse(fs, args, "apk", "keystore", "alias", "out"); err != nil {
		return err
	}
	root, bt, certs, err := signingSetup()
	if err != nil {
		return err
	}
	lineage := filepath.Join(root, lineageFile)
	if _, err := os.Stat(lineage); err != nil {
		return fmt.Errorf("没有 %s：生成步骤见 docs/release.md「生成正式发版密钥」", lineageFile)
	}
	printed, err := command(bt.tool("apksigner"), "lineage", "--in", lineage, "--print-certs")
	if err != nil {
		return err
	}
	if got := certDigests(printed); !slices.Equal(got, certs) {
		return fmt.Errorf("%s 里的证书是 %v，与 %s 登记的 %v 不符", lineageFile, got, certsFile, certs)
	}
	if os.Getenv(storePassEnv) == "" {
		return errors.New(storePassEnv + " 没设：密钥库的口令从这个环境变量取")
	}
	if err := os.MkdirAll(filepath.Dir(*out), 0o755); err != nil {
		return err
	}
	if _, err := command(bt.tool("apksigner"), signArgs(*apk, *keystore, *alias, lineage, *out, os.Getenv(keyPassEnv) != "")...); err != nil {
		return err
	}
	facts, err := inspect(bt, *out)
	if err != nil {
		return err
	}
	if err := checkSignature(facts, certs); err != nil {
		return err
	}
	fmt.Printf("签好了 %s：证书 %s，lineage 共 %d 把\n", *out, facts.Signers[0], len(facts.Lineage))
	return nil
}

// runVerify 核对一个签好的正式版包。
func runVerify(args []string) error {
	fs := flag.NewFlagSet("verify", flag.ContinueOnError)
	apk := fs.String("apk", "", "签好的包")
	version := fs.String("version", "", "应有的 versionName X.Y.Z")
	code := fs.Int("version-code", 0, "应有的 versionCode")
	if err := parse(fs, args, "apk", "version"); err != nil {
		return err
	}
	if *code <= 0 {
		return errors.New("缺少 -version-code")
	}
	_, bt, certs, err := signingSetup()
	if err != nil {
		return err
	}
	facts, err := inspect(bt, *apk)
	if err != nil {
		return err
	}
	if err := checkRelease(facts, certs, *version, *code); err != nil {
		return err
	}
	fmt.Printf("%s 核对过：%s %s（versionCode %d），只签 v3，证书 %s，lineage 共 %d 把\n",
		*apk, facts.Package, facts.VersionName, facts.VersionCode, facts.Signers[0], len(facts.Lineage))
	return nil
}
