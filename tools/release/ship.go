package main

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

var (
	shipRecheck  = 20 * time.Second
	shipRunWait  = 90 * time.Minute
	shipFindWait = 2 * time.Minute
)

// runSmoke 在仓根跑 R8 冒烟测试（docs/release.md「发版」），输出直接给终端。测试里替换掉。
var runSmoke = func(root string) error {
	cmd := exec.Command(filepath.Join(root, "tools/phone-run"), "tools/phone-tests/smoke.sh")
	cmd.Dir = root
	cmd.Env = append(os.Environ(), "ZETA_R8=1", "ZETA_PHONE_OWNER=release")
	cmd.Stdout, cmd.Stderr = os.Stderr, os.Stderr
	return cmd.Run()
}

// runShip 是发版的唯一入口（在维护者的主 checkout 上跑）：同时启动 release.yml 和本机的 R8 冒烟测试，
// 测试结果记成提交上的 commit status，publish 等它通过才发布；发完下载资产核对。-preview 只列这次会发什么。
func runShip(args []string) error {
	fs := flag.NewFlagSet("ship", flag.ContinueOnError)
	bump := fs.String("bump", "patch", "patch、minor 或 major（minor、major 要维护者先同意）")
	preview := fs.Bool("preview", false, "只列版本、versionCode 和发版说明，不发版")
	runner := fs.String("runner", "self-hosted", "门禁和构建跑在哪：self-hosted 或 github-hosted（本机 runner 不在线时）")
	if err := parse(fs, args); err != nil {
		return err
	}
	root, err := repoRoot()
	if err != nil {
		return err
	}
	repo, sha, err := shipTarget(root, !*preview)
	if err != nil {
		return err
	}
	p, err := planRelease(repo, sha, *bump, false)
	if err != nil {
		return err
	}
	if *preview {
		return printPreview(p, sha)
	}
	fmt.Fprintf(os.Stderr, "发 v%s（%s；versionCode %s；这个提交%s过全量门禁），源提交 %s\n", p.version, p.note, p.versionCode, p.gateWords(), sha)
	run, err := startRelease(repo, sha, *bump, *runner)
	if err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "release.yml 在跑：https://github.com/%s/actions/runs/%s\n", repo, run)
	if err := smokeOnce(root, repo, sha); err != nil {
		if _, cerr := gh("run", "cancel", run, "--repo", repo); cerr != nil {
			fmt.Fprintln(os.Stderr, "取消 release.yml 没成：", cerr)
		}
		return err
	}
	if err := waitRun(repo, run); err != nil {
		return err
	}
	return checkPublished(repo, sha, p.version)
}

// shipTarget 是这次发版的仓和提交：远端默认分支的最新提交。要跑冒烟测试时主 checkout 必须正好在它上面、没有改动，
// 本机构建的才是 CI 发的那个提交。
func shipTarget(root string, needCheckout bool) (repo, sha string, err error) {
	if repo, err = gh("repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"); err != nil {
		return "", "", err
	}
	if _, err = git("-C", root, "fetch", "--quiet", "origin", "master"); err != nil {
		return "", "", err
	}
	if sha, err = git("-C", root, "rev-parse", "origin/master"); err != nil {
		return "", "", err
	}
	if !needCheckout {
		return repo, sha, nil
	}
	head, err := git("-C", root, "rev-parse", "HEAD")
	if err != nil {
		return "", "", err
	}
	dirty, err := git("-C", root, "status", "--porcelain", "--untracked-files=no")
	if err != nil {
		return "", "", err
	}
	if head != sha || dirty != "" {
		return "", "", fmt.Errorf("%s 要正好在 origin/master（%.8s）上、没有改动：现在在 %.8s，改动 %q", root, sha, head, dirty)
	}
	return repo, sha, nil
}

func printPreview(p plan, sha string) error {
	tags, err := git("tag", "--list", "v*")
	if err != nil {
		return err
	}
	previous, err := previousTag(strings.Fields(tags), p.version)
	if err != nil {
		return err
	}
	titles, err := commitTitles(previous, sha)
	if err != nil {
		return err
	}
	fmt.Printf("v%s（%s），versionCode %s，源提交 %s，这个提交%s过全量门禁\n", p.version, p.note, p.versionCode, sha, p.gateWords())
	fmt.Printf("发版说明：%s 以来 %d 个提交\n%s\n", previous, len(titles), formatNotes(titles))
	return nil
}

// startRelease 触发 release.yml，返回这次运行的编号：触发之后、在这个提交上手动触发的那一个。
func startRelease(repo, sha, bump, runner string) (string, error) {
	since := time.Now().Add(-10 * time.Second).UTC()
	if _, err := gh("workflow", "run", "release.yml", "--repo", repo, "--ref", "master", "-f", "bump="+bump, "-f", "runner="+runner); err != nil {
		return "", err
	}
	deadline := time.Now().Add(shipFindWait)
	for {
		out, err := gh("run", "list", "--repo", repo, "--workflow", "release.yml", "--event", "workflow_dispatch",
			"--commit", sha, "--json", "databaseId,createdAt", "--jq", `.[] | "\(.databaseId)\t\(.createdAt)"`)
		if err != nil {
			return "", err
		}
		if id := newestRun(out, since); id != "" {
			return id, nil
		}
		if time.Now().After(deadline) {
			return "", errors.New("触发了 release.yml，但没找到这次的运行")
		}
		sleep(shipRecheck / 4)
	}
}

// newestRun 从「编号\t创建时间」各行里挑 since 之后创建的最新一个。
func newestRun(lines string, since time.Time) string {
	best, bestAt := "", since
	for _, line := range strings.Split(lines, "\n") {
		id, at, ok := strings.Cut(strings.TrimSpace(line), "\t")
		t, err := time.Parse(time.RFC3339, at)
		if !ok || err != nil || t.Before(bestAt) {
			continue
		}
		best, bestAt = id, t
	}
	return best
}

// smokeOnce 跑 R8 冒烟测试并把结果记在提交上；这个提交已经通过的（上次没发完、重跑）不再跑。
func smokeOnce(root, repo, sha string) error {
	if state, err := smokeState(repo, sha); err != nil {
		return err
	} else if state == "success" {
		fmt.Fprintln(os.Stderr, "这个提交的 R8 冒烟测试已经通过，不再跑")
		return nil
	}
	if err := setSmoke(repo, sha, "pending", "本机在跑 R8 冒烟测试"); err != nil {
		return err
	}
	if err := runSmoke(root); err != nil {
		if serr := setSmoke(repo, sha, "failure", "R8 冒烟测试没过"); serr != nil {
			fmt.Fprintln(os.Stderr, "记结果没成：", serr)
		}
		return fmt.Errorf("R8 冒烟测试没过（%w），已取消这次发版", err)
	}
	return setSmoke(repo, sha, "success", "R8 冒烟测试通过")
}

// waitRun 等 release.yml 跑完，打印各个 job 的结果；没成功就报错。
func waitRun(repo, run string) error {
	deadline := time.Now().Add(shipRunWait)
	for {
		out, err := gh("run", "view", run, "--repo", repo, "--json", "status,conclusion", "--jq", `"\(.status)\t\(.conclusion)"`)
		if err != nil {
			return err
		}
		status, conclusion, _ := strings.Cut(out, "\t")
		if status == "completed" {
			jobs, err := gh("run", "view", run, "--repo", repo, "--json", "jobs", "--jq", `.jobs[] | "  \(.name)：\(.conclusion)"`)
			if err == nil {
				fmt.Fprintln(os.Stderr, jobs)
			}
			if conclusion != "success" {
				return fmt.Errorf("release.yml 没成功（%s）：修好后重跑 ship，能续上", conclusion)
			}
			return nil
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("等了 %v，release.yml 还没跑完", shipRunWait)
		}
		sleep(shipRecheck)
	}
}

// checkPublished 核对发出去的：最新的 Release 是这个版本，下载下来的手机资产照 check-download 核对，出自这个提交。
func checkPublished(repo, sha, version string) error {
	tag := "v" + version
	latest, err := gh("api", "repos/"+repo+"/releases/latest", "--jq", ".tag_name")
	if err != nil {
		return err
	}
	if latest != tag {
		return fmt.Errorf("最新的 Release 是 %s，不是 %s", latest, tag)
	}
	dir, err := os.MkdirTemp("", "zeta-ship-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(dir)
	if _, err := gh("release", "download", tag, "--repo", repo, "--dir", dir,
		"--pattern", "Zeta-v*.apk", "--pattern", "Zeta-v*.apk.sha256", "--pattern", updateFile); err != nil {
		return err
	}
	u, _, err := checkDownload(dir)
	if err != nil {
		return err
	}
	if u.SourceCommit != sha {
		return fmt.Errorf("%s 出自 %s，不是 %s", tag, u.SourceCommit, sha)
	}
	fmt.Printf("已发布 %s（versionCode %d）：https://github.com/%s/releases/tag/%s\nAPK sha256 %s，证书 %s\n",
		tag, u.VersionCode, repo, tag, u.SHA256, u.Certificate)
	return nil
}
