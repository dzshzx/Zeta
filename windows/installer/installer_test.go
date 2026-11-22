package installer

import (
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/testinput"
)

// upgradeCode 是 Compose 桌面版（desktop/windows/Zeta.wxs）的 UpgradeCode。装 Go 版的 MSI 时靠它认出已装的旧版并卸掉，不能改。
const upgradeCode = "{D73FE446-DDAD-4182-B6F1-FC4F8AC025FB}"

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

// 预发布的版本只取 X.Y.Z；UpgradeCode、按用户安装、REINSTALLMODE=amus 和 Compose 版一样，所以装它直接替换旧版。
func TestProduct(t *testing.T) {
	m, _ := msi(t, "0.7.9-dev.3+gabc1234")
	props := properties(t, m)
	for k, want := range map[string]string{
		"ProductName":    "Zeta",
		"ProductVersion": "0.7.9",
		"Manufacturer":   "dzshzx",
		"REINSTALLMODE":  "amus",
		"ARPPRODUCTICON": "zeta.ico",
	} {
		if props[k] != want {
			t.Errorf("%s = %q, want %q", k, props[k], want)
		}
	}
	if !strings.EqualFold(props["UpgradeCode"], upgradeCode) {
		t.Errorf("UpgradeCode %s, want the Compose version's %s", props["UpgradeCode"], upgradeCode)
	}
	if v, ok := props["ALLUSERS"]; ok {
		t.Errorf("ALLUSERS = %q: the Compose version installs per user, and Windows only finds an old version in the same scope", v)
	}
	sum, err := exec.Command("msiinfo", "suminfo", m).Output()
	if err != nil {
		t.Fatal(err)
	}
	// Word Count 10 = 压缩（2）+ 不要管理员（8）
	if !regexp.MustCompile(`(?m)^Source: 10 `).Match(sum) {
		t.Errorf("summary information:\n%s", sum)
	}
}

// 认出同一个 UpgradeCode 的旧版本（含同版本）并在装新文件之前卸掉；更新的版本已装时不降级。
func TestUpgrade(t *testing.T) {
	m, _ := msi(t, "0.7.9")
	var older, same, newer bool
	for _, row := range table(t, m, "Upgrade") {
		if !strings.EqualFold(row["UpgradeCode"], upgradeCode) {
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
}

// 只装 Zeta.exe 一个文件，到 %LOCALAPPDATA%\Programs\Zeta（和 Compose 版同一个目录）；开始菜单的快捷方式起它。
func TestFilesAndShortcut(t *testing.T) {
	m, size := msi(t, "0.7.9")
	files := table(t, m, "File")
	if len(files) != 1 || files[0]["FileName"] != "Zeta.exe" || files[0]["FileSize"] != strconv.Itoa(size) || files[0]["Component_"] != "ZetaExe" {
		t.Errorf("files %v", files)
	}
	dirs := map[string][2]string{}
	for _, row := range table(t, m, "Directory") {
		dirs[row["Directory"]] = [2]string{row["Directory_Parent"], row["DefaultDir"]}
	}
	if dirs["INSTALLDIR"] != [2]string{"ProgramsDir", "Zeta"} || dirs["ProgramsDir"] != [2]string{"LocalAppDataFolder", "Programs"} {
		t.Errorf("directories %v", dirs)
	}
	for _, row := range table(t, m, "Component") {
		if row["Component"] == "ZetaExe" && row["Directory_"] != "INSTALLDIR" {
			t.Errorf("Zeta.exe goes to %s", row["Directory_"])
		}
	}
	shortcuts := table(t, m, "Shortcut")
	if len(shortcuts) != 1 || shortcuts[0]["Directory_"] != "ProgramMenuFolder" || shortcuts[0]["Name"] != "Zeta" ||
		shortcuts[0]["Target"] != "[INSTALLDIR]Zeta.exe" || shortcuts[0]["WkDir"] != "INSTALLDIR" {
		t.Errorf("shortcuts %v", shortcuts)
	}
}

// Zeta.wxs 写的就是 Compose 版的那个 UpgradeCode。Compose 版的经 windows/testdata 里指向 desktop/windows 的符号链接读
// （模块目录外的路径 go test 的缓存不认）。Compose 版（desktop/）删掉时连同这条核对和那个符号链接一起删，上面的常量留着。
func TestSameUpgradeCodeAsCompose(t *testing.T) {
	re := regexp.MustCompile(`UpgradeCode="([0-9a-fA-F-]+)"`)
	for _, path := range []string{"Zeta.wxs", "../testdata/desktop-windows/Zeta.wxs"} {
		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		m := re.FindSubmatch(data)
		if m == nil || !strings.EqualFold("{"+string(m[1])+"}", upgradeCode) {
			t.Errorf("%s: UpgradeCode %q", path, m)
		}
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
