package installer

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/testinput"
)

// variant 是 MSI 的一种身份（Zeta.wxs 开头）。正式版的各项不能改：UpgradeCode 是各个正式版共用的，装新版时靠它认出已装的旧版并卸掉；
// 目录、快捷方式、注册表键变了，装新版就会留下旧的那份。测试版（版本名不是干净的 X.Y.Z）是另一个产品，每一项都和正式版不同，能同时装着。
type variant struct {
	version     string // 交给 msi.sh 的版本名
	name        string // 产品名
	upgradeCode string
	dir         string // %LOCALAPPDATA%\Programs 下的目录（Directory 表的 DefaultDir，带空格的写成短名|长名）
	exe         string // 装上的 exe（File 表的 FileName）
	target      string // 开始菜单快捷方式起的程序
	shortcut    string // 快捷方式的组件 GUID：组件按 GUID 在系统里记数，两个产品共用一个会互相牵连
	regKey      string // 快捷方式组件的键路径
}

var (
	release = variant{"0.7.9", "Zeta", "{D73FE446-DDAD-4182-B6F1-FC4F8AC025FB}", "Zeta", "Zeta.exe", "[INSTALLDIR]Zeta.exe",
		"{0E6F0C7A-5A43-4D1E-9A52-3F0A8F6C2B71}", `Software\dzshzx\Zeta`}
	dev = variant{"0.7.9-dev.3+gabc1234", "Zeta Dev", "{B2AA700B-0F79-4508-B34A-BBF2039D05CA}", "ZETADEV|Zeta Dev", "ZETADEV.EXE|Zeta Dev.exe",
		"[INSTALLDIR]Zeta Dev.exe", "{32869AF5-C651-479C-8841-4B215F906F38}", `Software\dzshzx\Zeta Dev`}
	variants = []variant{release, dev}
)

// msi 用 msi.sh 拿一个假的 exe 打一个 MSI，返回它的路径和假 exe 的大小。
func msi(t *testing.T, version string) (string, int) {
	t.Helper()
	for _, tool := range []string{"wixl", "msiinfo"} {
		if _, err := exec.LookPath(tool); err != nil {
			if os.Getenv("CI") != "" {
				t.Skipf("CI 上没有 %s：不核对 MSI", tool)
			}
			t.Fatalf("缺 %s：sudo apt install wixl msitools", tool)
		}
	}
	dir := t.TempDir()
	exe := filepath.Join(dir, "Zeta.exe")
	body := []byte("MZ not really a program")
	if err := os.WriteFile(exe, body, 0o644); err != nil {
		t.Fatal(err)
	}
	// msi.sh 读本目录里的 Zeta.wxs、zeta.ico：子进程读的文件 go test 的缓存看不见，这里打开一遍本目录
	if err := testinput.Tree("."); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(dir, "Zeta.msi")
	if b, err := exec.Command("./msi.sh", exe, version, out).CombinedOutput(); err != nil {
		t.Fatalf("msi.sh: %v\n%s", err, b)
	}
	return out, len(body)
}

// table 读 MSI 的一张表：msiinfo export 的前三行是列名、列类型、表名和主键，之后一行一条记录。
func table(t *testing.T, msi, name string) []map[string]string {
	t.Helper()
	out, err := exec.Command("msiinfo", "export", msi, name).Output()
	if err != nil {
		t.Fatalf("msiinfo export %s: %v", name, err)
	}
	lines := strings.Split(strings.TrimRight(strings.ReplaceAll(string(out), "\r\n", "\n"), "\n"), "\n")
	cols := strings.Split(lines[0], "\t")
	var rows []map[string]string
	for _, line := range lines[3:] {
		row := map[string]string{}
		for i, v := range strings.Split(line, "\t") {
			row[cols[i]] = v
		}
		rows = append(rows, row)
	}
	return rows
}

func properties(t *testing.T, msi string) map[string]string {
	props := map[string]string{}
	for _, row := range table(t, msi, "Property") {
		props[row["Property"]] = row["Value"]
	}
	return props
}

// 两个版本的产品信息：MSI 的版本只取 X.Y.Z；各自的 UpgradeCode、按用户安装、REINSTALLMODE=amus，所以装它直接替换同一版本已装的旧版。
func TestProduct(t *testing.T) {
	for _, v := range variants {
		t.Run(v.name, func(t *testing.T) {
			m, _ := msi(t, v.version)
			props := properties(t, m)
			for k, want := range map[string]string{
				"ProductName":    v.name,
				"ProductVersion": "0.7.9",
				"Manufacturer":   "dzshzx",
				"REINSTALLMODE":  "amus",
				"ARPPRODUCTICON": "zeta.ico",
			} {
				if props[k] != want {
					t.Errorf("%s = %q, want %q", k, props[k], want)
				}
			}
			if !strings.EqualFold(props["UpgradeCode"], v.upgradeCode) {
				t.Errorf("UpgradeCode %s, want %s", props["UpgradeCode"], v.upgradeCode)
			}
			if v, ok := props["ALLUSERS"]; ok {
				t.Errorf("ALLUSERS = %q: every version installs per user, and Windows only finds an old version in the same scope", v)
			}
			sum, err := exec.Command("msiinfo", "suminfo", m).Output()
			if err != nil {
				t.Fatal(err)
			}
			// Word Count 10 = 压缩（2）+ 不要管理员（8）
			if !regexp.MustCompile(`(?m)^Source: 10 `).Match(sum) {
				t.Errorf("summary information:\n%s", sum)
			}
		})
	}
}

// 测试版的每一项身份都和正式版不同：装测试版不会认出、卸掉或改动正式版。
func TestVariantsApart(t *testing.T) {
	if release.name == dev.name || strings.EqualFold(release.upgradeCode, dev.upgradeCode) || release.dir == dev.dir ||
		release.target == dev.target || strings.EqualFold(release.shortcut, dev.shortcut) || release.regKey == dev.regKey {
		t.Errorf("release %+v and dev %+v share an identity", release, dev)
	}
	// 两个的图标不同（ARPPRODUCTICON 的 Icon 表里是 zeta.ico、zeta-dev.ico 的内容）
	a, errA := os.ReadFile("zeta.ico")
	b, errB := os.ReadFile("zeta-dev.ico")
	if errA != nil || errB != nil || bytes.Equal(a, b) {
		t.Errorf("icons: %v %v, same %v", errA, errB, bytes.Equal(a, b))
	}
}

// 认出同一个 UpgradeCode 的旧版本（含同版本）并在装新文件之前卸掉；更新的版本已装时不降级。只认自己那个版本的 UpgradeCode。
func TestUpgrade(t *testing.T) {
	for _, v := range variants {
		t.Run(v.name, func(t *testing.T) {
			m, _ := msi(t, v.version)
			var older, same, newer bool
			for _, row := range table(t, m, "Upgrade") {
				if !strings.EqualFold(row["UpgradeCode"], v.upgradeCode) {
					t.Errorf("an upgrade row for another product: %v", row)
					continue
				}
				switch row["ActionProperty"] {
				case "WIX_UPGRADE_DETECTED":
					older = row["VersionMin"] == "" && row["VersionMax"] == "0.7.9"
				case "WIX_SAME_VERSION_UPGRADE_DETECTED":
					same = row["VersionMin"] == "0.7.9" && row["VersionMax"] == "0.7.9"
				case "WIX_DOWNGRADE_DETECTED":
					newer = row["VersionMin"] == "0.7.9"
				}
			}
			if !older || !same || !newer {
				t.Errorf("upgrade rows: older %v, same version %v, newer %v", older, same, newer)
			}
			seq := map[string]string{}
			for _, row := range table(t, m, "InstallExecuteSequence") {
				seq[row["Action"]] = row["Sequence"]
			}
			if mustAtoi(t, seq["RemoveExistingProducts"]) > mustAtoi(t, seq["InstallFiles"]) {
				t.Errorf("RemoveExistingProducts at %q, InstallFiles at %q", seq["RemoveExistingProducts"], seq["InstallFiles"])
			}
		})
	}
}

// 只装一个 exe，到 %LOCALAPPDATA%\Programs\<名字>；开始菜单的快捷方式起它，键路径是 HKCU 下各自的注册表键。
func TestFilesAndShortcut(t *testing.T) {
	for _, v := range variants {
		t.Run(v.name, func(t *testing.T) {
			m, size := msi(t, v.version)
			files := table(t, m, "File")
			if len(files) != 1 || files[0]["FileName"] != v.exe || files[0]["FileSize"] != strconv.Itoa(size) || files[0]["Component_"] != "ZetaExe" {
				t.Errorf("files %v", files)
			}
			dirs := map[string][2]string{}
			for _, row := range table(t, m, "Directory") {
				dirs[row["Directory"]] = [2]string{row["Directory_Parent"], row["DefaultDir"]}
			}
			if dirs["INSTALLDIR"] != [2]string{"ProgramsDir", v.dir} || dirs["ProgramsDir"] != [2]string{"LocalAppDataFolder", "Programs"} {
				t.Errorf("directories %v", dirs)
			}
			components := map[string]map[string]string{}
			for _, row := range table(t, m, "Component") {
				components[row["Component"]] = row
			}
			if components["ZetaExe"]["Directory_"] != "INSTALLDIR" {
				t.Errorf("the exe goes to %s", components["ZetaExe"]["Directory_"])
			}
			if !strings.EqualFold(components["StartMenuShortcut"]["ComponentId"], v.shortcut) {
				t.Errorf("shortcut component %v", components["StartMenuShortcut"])
			}
			shortcuts := table(t, m, "Shortcut")
			if len(shortcuts) != 1 || shortcuts[0]["Directory_"] != "ProgramMenuFolder" || shortcuts[0]["Name"] != v.dir ||
				shortcuts[0]["Target"] != v.target || shortcuts[0]["WkDir"] != "INSTALLDIR" {
				t.Errorf("shortcuts %v", shortcuts)
			}
			checkRegistry(t, m, v)
		})
	}
}

// checkRegistry：两个值都在快捷方式组件下、HKCU 的同一个键里：installed 是键路径，InstallDir 记安装位置（TestInstallDir）。
func checkRegistry(t *testing.T, m string, v variant) {
	t.Helper()
	regs := map[string]string{}
	for _, row := range table(t, m, "Registry") {
		if row["Root"] != "1" || row["Key"] != v.regKey || row["Component_"] != "StartMenuShortcut" {
			t.Errorf("registry %v", row)
		}
		regs[row["Name"]] = row["Value"]
	}
	if len(regs) != 2 || regs["installed"] != "#1" || regs["InstallDir"] != "[INSTALLDIR]" {
		t.Errorf("registry values %v", regs)
	}
}

func mustAtoi(t *testing.T, s string) int {
	t.Helper()
	n, err := strconv.Atoi(s)
	if err != nil {
		t.Fatalf("not a number: %q", s)
	}
	return n
}
