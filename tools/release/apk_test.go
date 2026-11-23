package main

import (
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

func TestParseCerts(t *testing.T) {
	certs, err := parseCerts("# 注释\n\n" + oldCert + "\n  " + newCert + "  \n")
	if err != nil || !slices.Equal(certs, []string{oldCert, newCert}) {
		t.Errorf("certs %v, %v", certs, err)
	}
	if _, err := parseCerts("# 只有调试证书\n" + oldCert + "\n"); err == nil || !strings.Contains(err.Error(), "还没登记") {
		t.Errorf("one certificate means the release certificate is not registered yet: %v", err)
	}
	if _, err := parseCerts(oldCert + "\n" + strings.ToUpper(newCert) + "\n"); err == nil || !strings.Contains(err.Error(), "第 2 行") {
		t.Errorf("a digest must be lowercase hex: %v", err)
	}
}

func TestParseVerify(t *testing.T) {
	schemes, signers := parseVerify(verifyRotated)
	if !schemes["v3"] || schemes["v2"] || schemes["v1"] || schemes["v3.1"] || !slices.Equal(signers, []string{newCert}) {
		t.Errorf("rotated: schemes %v, signers %v", schemes, signers)
	}
	schemes, signers = parseVerify(verifyDebugV2)
	if !schemes["v2"] || schemes["v3"] || !slices.Equal(signers, []string{oldCert}) {
		t.Errorf("debug: schemes %v, signers %v", schemes, signers)
	}
	if got := certDigests(lineageOut); !slices.Equal(got, []string{oldCert, newCert}) {
		t.Errorf("lineage %v", got)
	}
}

func TestParseBadging(t *testing.T) {
	pkg, name, code, err := parseBadging(badging(packageName, "1234", "0.7.1"))
	if pkg != packageName || name != "0.7.1" || code != 1234 || err != nil {
		t.Errorf("%q %q %d %v", pkg, name, code, err)
	}
	if _, _, _, err := parseBadging("minSdkVersion:'35'\n"); err == nil {
		t.Error("output without the package line fails")
	}
}

func TestCheckRelease(t *testing.T) {
	certs := []string{oldCert, newCert}
	good := func() apkFacts {
		return apkFacts{Package: packageName, VersionName: "0.7.1", VersionCode: 1234,
			Schemes: map[string]bool{"v3": true}, Signers: []string{newCert}, Lineage: []string{oldCert, newCert}, Aligned: true}
	}
	if err := checkRelease(good(), certs, "0.7.1", 1234); err != nil {
		t.Fatalf("a good release: %v", err)
	}
	cases := []struct {
		name   string
		change func(*apkFacts)
		want   string
	}{
		{"another package", func(f *apkFacts) { f.Package = packageName + ".dev" }, "包名"},
		{"a dev version name", func(f *apkFacts) { f.VersionName = "0.7.2-dev.3+gabc1234" }, "versionName"},
		{"another version code", func(f *apkFacts) { f.VersionCode = 1233 }, "versionCode"},
		{"v2 beside v3", func(f *apkFacts) { f.Schemes["v2"] = true }, "带着 v2"},
		{"no v3", func(f *apkFacts) { f.Schemes = map[string]bool{"v2": true} }, "没有 APK 签名方案 v3"},
		{"signed by the debug key", func(f *apkFacts) { f.Signers = []string{oldCert} }, "不是 " + certsFile + " 登记的正式证书"},
		{"no lineage", func(f *apkFacts) { f.Lineage = nil }, "没法覆盖升级"},
		{"unaligned", func(f *apkFacts) { f.Aligned = false }, "zipalign"},
	}
	for _, c := range cases {
		f := good()
		c.change(&f)
		if err := checkRelease(f, certs, "0.7.1", 1234); err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: %v", c.name, err)
		}
	}
}

func TestSignArgsKeepPasswordsOffTheCommandLine(t *testing.T) {
	t.Setenv(storePassEnv, "store-secret")
	t.Setenv(keyPassEnv, "key-secret")
	args := strings.Join(signArgs("in.apk", "k.jks", "zeta", "lineage.bin", "out.apk", true), " ")
	for _, want := range []string{"--ks k.jks --ks-key-alias zeta", "--ks-pass env:" + storePassEnv, "--key-pass env:" + keyPassEnv,
		"--lineage lineage.bin", "--v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false",
		"--out out.apk in.apk"} {
		if !strings.Contains(args, want) {
			t.Errorf("args lack %q: %s", want, args)
		}
	}
	if strings.Contains(args, "secret") {
		t.Errorf("a password reached the command line: %s", args)
	}
	if args := strings.Join(signArgs("in.apk", "k.jks", "zeta", "l", "o", false), " "); strings.Contains(args, "--key-pass") {
		t.Errorf("without a key password apksigner uses the store password: %s", args)
	}
}

func TestRunSignRefusesAMismatchedLineage(t *testing.T) {
	root := fakeRepo(t, oldCert+"\n"+newCert+"\n")
	t.Setenv(storePassEnv, "x")
	calls := fakeCommands(t, map[string]string{
		"apksigner lineage --in " + root + "/" + lineageFile: strings.Replace(lineageOut, oldCert, publicKey, 1),
	})
	err := runSign([]string{"-apk", "/in.apk", "-keystore", "/k.jks", "-alias", "zeta", "-out", t.TempDir() + "/Zeta.apk"})
	if err == nil || !strings.Contains(err.Error(), "不符") || slices.ContainsFunc(*calls, func(c string) bool { return strings.HasPrefix(c, "apksigner sign") }) {
		t.Errorf("a lineage that differs from the registered certificates is refused before signing: %v, %q", err, *calls)
	}
}

func TestRunSignSignsAndChecks(t *testing.T) {
	root := fakeRepo(t, oldCert+"\n"+newCert+"\n")
	t.Setenv(storePassEnv, "x")
	t.Setenv(keyPassEnv, "")
	out := t.TempDir() + "/signed/Zeta.apk"
	calls := fakeCommands(t, map[string]string{
		"apksigner lineage": lineageOut,
		"apksigner verify":  verifyRotated,
		"aapt2 dump":        badging(packageName, "1", "0.7.1"),
	})
	if err := runSign([]string{"-apk", "/in.apk", "-keystore", "/k.jks", "-alias", "zeta", "-out", out}); err != nil {
		t.Fatal(err)
	}
	want := "apksigner sign --ks /k.jks --ks-key-alias zeta --ks-pass env:" + storePassEnv + " --lineage " + root + "/" + lineageFile
	if !slices.ContainsFunc(*calls, func(c string) bool { return strings.HasPrefix(c, want) }) {
		t.Errorf("no %q in %q", want, *calls)
	}

	// 签出来的包要是只带调试证书、没有 lineage，签名这一步就失败
	fakeCommands(t, map[string]string{
		"apksigner lineage --in " + root: lineageOut,
		"apksigner lineage --in " + out:  "!The provided APK does not contain a valid lineage.",
		"apksigner verify":               verifyDebugV2,
		"aapt2 dump":                     badging(packageName, "1", "0.7.1"),
	})
	if err := runSign([]string{"-apk", "/in.apk", "-keystore", "/k.jks", "-alias", "zeta", "-out", out}); err == nil || !strings.Contains(err.Error(), "没法覆盖升级") {
		t.Errorf("a result without the lineage fails: %v", err)
	}
}

func TestSigningNeedsTheRegisteredReleaseCertificate(t *testing.T) {
	fakeRepo(t, "# 只有调试证书\n"+oldCert+"\n")
	fakeCommands(t, nil)
	err := runVerify([]string{"-apk", "/x.apk", "-version", "0.7.1", "-version-code", "1"})
	if err == nil || !strings.Contains(err.Error(), "生成正式发版密钥") {
		t.Errorf("verify points at the key generation steps: %v", err)
	}
}

// 没设 JAVA_HOME 时照仓里的 tools/java-home 找（工作站没有全局 java，ship 发完核对下载要用 apksigner）
func TestFindBuildToolsJavaHome(t *testing.T) {
	root := fakeRepo(t, oldCert+"\n"+newCert+"\n")
	script := filepath.Join(root, "tools", "java-home")
	if err := os.WriteFile(script, []byte("#!/bin/sh\necho /opt/jdk-25\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("JAVA_HOME", "")
	if _, err := findBuildTools(root); err != nil || os.Getenv("JAVA_HOME") != "/opt/jdk-25" {
		t.Errorf("没设时照 tools/java-home：err %v，JAVA_HOME %q", err, os.Getenv("JAVA_HOME"))
	}
	t.Setenv("JAVA_HOME", "/given")
	if _, err := findBuildTools(root); err != nil || os.Getenv("JAVA_HOME") != "/given" {
		t.Errorf("设了就用设的：err %v，JAVA_HOME %q", err, os.Getenv("JAVA_HOME"))
	}
}
