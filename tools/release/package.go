package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"time"
)

const updateFile = "update.json"

var checksumEntry = regexp.MustCompile(`^([0-9a-f]{64})  (.+)$`)

func apkAsset(version string) string { return "Zeta-v" + version + ".apk" }

// msiAsset 是 Windows 桌面版的安装包（未做代码签名）。
func msiAsset(version string) string { return "Zeta-v" + version + ".msi" }

// assetNames 是一次 Release 的资产，按名字排好：手机的 APK、它的 .sha256 和 update.json；desktop 时再加桌面版的
// MSI 和它的 .sha256（发布时是全套；tools/install.sh 只下手机的那几个）。
func assetNames(version string, desktop bool) []string {
	names := []string{apkAsset(version), apkAsset(version) + ".sha256", updateFile}
	if desktop {
		names = append(names, msiAsset(version), msiAsset(version)+".sha256")
	}
	slices.Sort(names)
	return names
}

// update 是 update.json：应用内检查更新读的就是它，字段的说明见 docs/release.md「update.json」。
type update struct {
	VersionCode  int    `json:"versionCode"`
	VersionName  string `json:"versionName"`
	Tag          string `json:"tag"`
	PublishedAt  string `json:"publishedAt"`
	Notes        string `json:"notes"`
	APK          string `json:"apk"`
	URL          string `json:"url"`
	Size         int64  `json:"size"`
	SHA256       string `json:"sha256"`
	Certificate  string `json:"certificateSha256"`
	SourceCommit string `json:"sourceCommit"`
	// 桌面版的 MSI：文件名、下载地址、字节数、sha256，桌面版的应用内更新读它们。v0.7.5 及更早的清单没有。
	MSI       string `json:"msi"`
	MSIURL    string `json:"msiUrl"`
	MSISize   int64  `json:"msiSize"`
	MSISHA256 string `json:"msiSha256"`
}

// formatNotes 把提交标题排成 Markdown 列表，旧的在前。
func formatNotes(titles []string) string {
	if len(titles) == 0 {
		return "- 没有新的提交"
	}
	lines := make([]string, len(titles))
	for i, t := range titles {
		lines[i] = "- " + t
	}
	return strings.Join(lines, "\n")
}

// commitTitles 是上一个正式版的 tag 之后、到 commit 为止的提交标题，旧的在前；没有上一个 tag 时数全部历史。
func commitTitles(previous, commit string) ([]string, error) {
	span := commit
	if previous != "" {
		span = previous + ".." + commit
	}
	out, err := git("log", "--reverse", "--no-merges", "--format=%s", span)
	if err != nil || out == "" {
		return nil, err
	}
	return strings.Split(out, "\n"), nil
}

// writeAssets 把签好的包拷成 Zeta-vX.Y.Z.apk，桌面版的 msi 拷成 Zeta-vX.Y.Z.msi，各写一个 .sha256（sha256sum 的格式），
// 再写 update.json。u 里与这两个文件有关的字段（apk、url、size、sha256 和 msi 开头的四项）由这里填。
func writeAssets(dir, apk, msi, repo string, u update) (update, error) {
	download := "https://github.com/" + repo + "/releases/download/" + u.Tag + "/"
	var err error
	u.MSI = msiAsset(u.VersionName)
	u.MSIURL = download + u.MSI
	if u.MSISize, u.MSISHA256, err = copyWithChecksum(msi, filepath.Join(dir, u.MSI)); err != nil {
		return u, err
	}
	u.APK = apkAsset(u.VersionName)
	u.URL = download + u.APK
	if u.Size, u.SHA256, err = copyWithChecksum(apk, filepath.Join(dir, u.APK)); err != nil {
		return u, err
	}
	data, err := json.MarshalIndent(u, "", "  ")
	if err != nil {
		return u, err
	}
	return u, os.WriteFile(filepath.Join(dir, updateFile), append(data, '\n'), 0o644)
}

// copyWithChecksum 把 src 拷到 dst，旁边写 dst.sha256，返回 dst 的字节数和 sha256。
func copyWithChecksum(src, dst string) (int64, string, error) {
	if err := copyFile(src, dst); err != nil {
		return 0, "", err
	}
	info, err := os.Stat(dst)
	if err != nil {
		return 0, "", err
	}
	sum, err := fileHash(dst)
	if err != nil {
		return 0, "", err
	}
	return info.Size(), sum, os.WriteFile(dst+".sha256", []byte(sum+"  "+filepath.Base(dst)+"\n"), 0o644)
}

// checkAssets 核对一套资产：正好是 assetNames 那几个文件（desktop 时含桌面版的 MSI），各个 .sha256 对得上，
// update.json 写的版本和 APK 的文件名、地址、大小、sha256 与文件相符；desktop 时 msi 开头的四项也要与 MSI 相符
// （check-download 只下手机的那几个，不管它们）。返回读到的 update.json。
func checkAssets(dir, version string, desktop bool) (update, error) {
	var u update
	entries, err := os.ReadDir(dir)
	if err != nil {
		return u, err
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	slices.Sort(names)
	if want := assetNames(version, desktop); !slices.Equal(names, want) {
		return u, fmt.Errorf("%s 里是 %v，应该正好是 %v", dir, names, want)
	}
	data, err := os.ReadFile(filepath.Join(dir, updateFile))
	if err != nil {
		return u, err
	}
	if err := json.Unmarshal(data, &u); err != nil {
		return u, fmt.Errorf("%s 读不了：%w", updateFile, err)
	}
	var problems []error
	if u.VersionName != version || u.Tag != "v"+version {
		problems = append(problems, fmt.Errorf("%s 的 versionName、tag 是 %q、%q，应该是 %q、%q", updateFile, u.VersionName, u.Tag, version, "v"+version))
	}
	if u.VersionCode <= 0 || !certHex.MatchString(u.Certificate) || u.SourceCommit == "" {
		problems = append(problems, fmt.Errorf("%s 缺 versionCode、certificateSha256 或 sourceCommit", updateFile))
	}
	files := []listed{{"apk", "url", "size", "sha256", u.APK, u.URL, u.Size, u.SHA256, apkAsset(version)}}
	if desktop {
		files = append(files, listed{"msi", "msiUrl", "msiSize", "msiSha256", u.MSI, u.MSIURL, u.MSISize, u.MSISHA256, msiAsset(version)})
	}
	for _, f := range files {
		found, err := f.check(dir, version)
		if err != nil {
			return u, err
		}
		problems = append(problems, found...)
	}
	return u, errors.Join(problems...)
}

// listed 是 update.json 里写的一个文件：四项的键名、写的值，和它在这个 Release 里应有的文件名 asset。
type listed struct {
	nameKey, urlKey, sizeKey, sumKey string
	name, url                        string
	size                             int64
	sum, asset                       string
}

// check 核对文件本身（.sha256 对得上；出错时返回 error），再核对清单写的四项与文件相符，不符的各一条。
func (f listed) check(dir, version string) ([]error, error) {
	path := filepath.Join(dir, f.asset)
	sum, err := checkChecksum(path)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	var problems []error
	expect := func(field, got, want string) {
		if got != want {
			problems = append(problems, fmt.Errorf("%s 的 %s 是 %q，应该是 %q", updateFile, field, got, want))
		}
	}
	expect(f.nameKey, f.name, f.asset)
	expect(f.sumKey, f.sum, sum)
	expect(f.sizeKey, fmt.Sprint(f.size), fmt.Sprint(info.Size()))
	if !strings.HasSuffix(f.url, "/releases/download/v"+version+"/"+f.asset) {
		problems = append(problems, fmt.Errorf("%s 的 %s %q 不指向这个 Release 的 %s", updateFile, f.urlKey, f.url, f.asset))
	}
	return problems, nil
}

// checkChecksum 核对 <文件>.sha256（「<sha256>  <文件名>」一行），返回文件的 sha256。
func checkChecksum(path string) (string, error) {
	entry, err := os.ReadFile(path + ".sha256")
	if err != nil {
		return "", err
	}
	m := checksumEntry.FindStringSubmatch(strings.TrimSpace(string(entry)))
	if m == nil || m[2] != filepath.Base(path) {
		return "", fmt.Errorf("%s.sha256 的格式不对：%q", filepath.Base(path), entry)
	}
	got, err := fileHash(path)
	if err != nil {
		return "", err
	}
	if got != m[1] {
		return "", fmt.Errorf("%s 的 sha256 是 %s，与 .sha256 里的 %s 不符", filepath.Base(path), got, m[1])
	}
	return got, nil
}

// releaseBody 是 Release 的说明：版本、源提交、证书、桌面版的安装包，和上一个正式版以来的提交标题。
func releaseBody(u update) string {
	return fmt.Sprintf("Zeta %s（versionCode %d）\n\n- source_commit: `%s`\n- 签名证书 SHA-256：`%s`\n"+
		"- Windows 桌面版：`%s`（未做代码签名，第一次运行时 Windows 会提示）\n\n## 改动\n\n%s\n",
		u.VersionName, u.VersionCode, u.SourceCommit, u.Certificate, msiAsset(u.VersionName), u.Notes)
}

// assetSummary 是写进运行摘要的一段：版本、源提交、每个资产的大小和 sha256，再加发布说明。
func assetSummary(dir string, u update) (string, error) {
	lines := []string{"## 发版资产 " + u.Tag, "",
		fmt.Sprintf("- 版本：`%s`（versionCode `%d`）", u.VersionName, u.VersionCode),
		"- source_commit: `" + u.SourceCommit + "`",
		"- 签名证书 SHA-256：`" + u.Certificate + "`", "",
		"| 资产 | 字节 | sha256 |", "| --- | --- | --- |"}
	for _, name := range assetNames(u.VersionName, true) {
		path := filepath.Join(dir, name)
		info, err := os.Stat(path)
		if err != nil {
			return "", err
		}
		sum, err := fileHash(path)
		if err != nil {
			return "", err
		}
		lines = append(lines, fmt.Sprintf("| %s | %d | `%s` |", name, info.Size(), sum))
	}
	lines = append(lines, "", "### Release 说明", "", releaseBody(u))
	return strings.Join(lines, "\n"), nil
}

// runPackage 核对签好的包，写出 Release 的全部资产，并把它们写进运行摘要。
func runPackage(args []string) error {
	fs := flag.NewFlagSet("package", flag.ContinueOnError)
	apk := fs.String("apk", "", "签好的包")
	msi := fs.String("msi", "", "桌面版的 Windows 安装包（未做代码签名）")
	version := fs.String("version", "", "这次的版本 X.Y.Z")
	code := fs.Int("version-code", 0, "这次的 versionCode")
	commit := fs.String("commit", "", "源提交（完整的提交号）")
	repo := fs.String("repo", "", "GitHub 仓库 owner/name，用来写下载地址")
	out := fs.String("out", "", "空目录，只放这次的资产")
	if err := parse(fs, args, "apk", "msi", "version", "commit", "repo", "out"); err != nil {
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
	if err := emptyDir(*out); err != nil {
		return err
	}
	tags, err := git("tag", "--list", "v*")
	if err != nil {
		return err
	}
	previous, err := previousTag(strings.Fields(tags), *version)
	if err != nil {
		return err
	}
	titles, err := commitTitles(previous, *commit)
	if err != nil {
		return err
	}
	u, err := writeAssets(*out, *apk, *msi, *repo, update{
		VersionCode: facts.VersionCode, VersionName: facts.VersionName, Tag: "v" + *version,
		PublishedAt: time.Now().UTC().Format(time.RFC3339), Notes: formatNotes(titles),
		Certificate: facts.Signers[0], SourceCommit: *commit,
	})
	if err != nil {
		return err
	}
	if _, err := checkAssets(*out, *version, true); err != nil {
		return err
	}
	summary, err := assetSummary(*out, u)
	if err != nil {
		return err
	}
	fmt.Print(summary)
	return stepSummary(summary)
}

// runCheckDownload 核对 gh release download 下来的手机那几个资产（不含桌面版的 MSI）：资产本身一致，包的签名证书是本仓登记的正式证书，
// 版本与 update.json 相符。通过后在标准输出打印 APK 的路径。
func runCheckDownload(args []string) error {
	fs := flag.NewFlagSet("check-download", flag.ContinueOnError)
	dir := fs.String("dir", "", "下载下来的资产所在的目录")
	if err := parse(fs, args, "dir"); err != nil {
		return err
	}
	u, apk, err := checkDownload(*dir)
	if err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "%s 核对过：%s（versionCode %d），sha256 %s，证书 %s\n", u.APK, u.VersionName, u.VersionCode, u.SHA256, u.Certificate)
	fmt.Println(apk)
	return nil
}

// checkDownload 核对 dir 里下载下来的手机那几个资产，返回 update.json 和 APK 的路径（check-download 和 ship 共用）。
func checkDownload(dir string) (update, string, error) {
	data, err := os.ReadFile(filepath.Join(dir, updateFile))
	if err != nil {
		return update{}, "", err
	}
	var head update
	if err := json.Unmarshal(data, &head); err != nil {
		return update{}, "", fmt.Errorf("%s 读不了：%w", updateFile, err)
	}
	u, err := checkAssets(dir, head.VersionName, false)
	if err != nil {
		return update{}, "", err
	}
	_, bt, certs, err := signingSetup()
	if err != nil {
		return update{}, "", err
	}
	apk := filepath.Join(dir, u.APK)
	facts, err := inspect(bt, apk)
	if err != nil {
		return update{}, "", err
	}
	if err := checkRelease(facts, certs, u.VersionName, u.VersionCode); err != nil {
		return update{}, "", err
	}
	if u.Certificate != facts.Signers[0] {
		return update{}, "", fmt.Errorf("%s 写的证书 %s 与包的签名证书 %s 不符", updateFile, u.Certificate, facts.Signers[0])
	}
	return u, apk, nil
}
