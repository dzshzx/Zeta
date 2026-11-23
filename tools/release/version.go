package main

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

var releaseTag = regexp.MustCompile(`^v(\d+)\.(\d+)\.(\d+)$`)

// semver 是正式版的版本：major、minor、patch。
type semver [3]int

func (v semver) String() string { return fmt.Sprintf("%d.%d.%d", v[0], v[1], v[2]) }

// parseTag 只认纯 vX.Y.Z（三段数字、没有前后缀）。
func parseTag(tag string) (semver, bool) {
	m := releaseTag.FindStringSubmatch(tag)
	if m == nil {
		return semver{}, false
	}
	var v semver
	for i := range v {
		n, err := strconv.Atoi(m[i+1])
		if err != nil {
			return semver{}, false
		}
		v[i] = n
	}
	return v, true
}

// highest 是 tags 里按数字比较最高的纯 vX.Y.Z，别的 tag 不算；below 非空时只看比它低的。
func highest(tags []string, below *semver) (semver, string, bool) {
	var best semver
	name := ""
	for _, tag := range tags {
		v, ok := parseTag(tag)
		if !ok || (below != nil && slices.Compare(v[:], below[:]) >= 0) {
			continue
		}
		if name == "" || slices.Compare(v[:], best[:]) > 0 {
			best, name = v, tag
		}
	}
	return best, name, name != ""
}

// previousTag 是 version 之前的那个正式版的 tag（发布说明从它数起）；没有就是空。
func previousTag(tags []string, version string) (string, error) {
	v, ok := parseTag("v" + version)
	if !ok {
		return "", fmt.Errorf("版本 %q 不是 X.Y.Z", version)
	}
	_, name, _ := highest(tags, &v)
	return name, nil
}

// releaseVersion 是这次发版要构建的版本。发过版指提交上有纯 vX.Y.Z tag、且同名 Release 已发布；
// 有 tag 没有已发布的 Release 是上次没发完，沿用那个版本。否则最高的 tag 按 bump 升一档。
// published 列出已发布的 Release，只在提交带 tag 时调用。note 说明版本是怎么来的。
func releaseVersion(tags, headTags []string, published func() ([]string, error), bump string, dryRun bool) (version, note string, err error) {
	base, _, ok := highest(tags, nil)
	if !ok {
		return "", "", errors.New("仓里没有纯 vX.Y.Z tag，不知道从哪个版本升")
	}
	if head, headTag, onHead := highest(headTags, nil); onHead {
		names, err := published()
		if err != nil {
			return "", "", err
		}
		if !slices.Contains(names, headTag) {
			return head.String(), "续发 " + headTag + "：这个提交已有这个 tag，同名 Release 还没发布", nil
		}
		if !dryRun {
			return "", "", fmt.Errorf("这个提交已发过版（%s，Release 已发布）；有新提交后再发", headTag)
		}
		note = headTag + " 已从这个提交发布过，dry run 只预演下一档；"
	}
	next := base
	switch bump {
	case "patch":
		next[2]++
	case "minor":
		next = semver{base[0], base[1] + 1, 0}
	case "major":
		next = semver{base[0] + 1, 0, 0}
	default:
		return "", "", fmt.Errorf("不认识的 bump %q（patch、minor、major）", bump)
	}
	return next.String(), note + "基线 v" + base.String() + "，升 " + bump, nil
}

// plan 是这次发版的打算：版本、版本怎么来的、versionCode。
type plan struct {
	version, note, versionCode string
}

// planRelease 算 sha 这个提交这次发什么版。
func planRelease(repo, sha, bump string, dryRun bool) (plan, error) {
	tags, err := git("tag", "--list", "v*")
	if err != nil {
		return plan{}, err
	}
	headTags, err := git("tag", "--points-at", sha)
	if err != nil {
		return plan{}, err
	}
	published := func() ([]string, error) {
		out, err := gh("api", "repos/"+repo+"/releases?per_page=100", "--paginate",
			"--jq", ".[] | select(.draft | not) | .tag_name")
		return strings.Fields(out), err
	}
	var p plan
	if p.version, p.note, err = releaseVersion(strings.Fields(tags), strings.Fields(headTags), published, bump, dryRun); err != nil {
		return plan{}, err
	}
	if p.versionCode, err = git("rev-list", "--count", sha); err != nil {
		return plan{}, err
	}
	return p, nil
}

// runNextVersion 在 GitHub Actions 里打印这次发版的 version、tag 和 version_code，供 $GITHUB_OUTPUT。
func runNextVersion(args []string) error {
	fs := flag.NewFlagSet("next-version", flag.ContinueOnError)
	bump := fs.String("bump", "", "patch、minor 或 major")
	dryRun := fs.Bool("dry-run", false, "dry run，任何分支都能跑")
	branch := fs.String("branch", "", "默认分支，正式发版只在它上面跑")
	if err := parse(fs, args, "bump", "branch"); err != nil {
		return err
	}
	env, err := actionsEnv("GITHUB_REF", "GITHUB_SHA", "GITHUB_REPOSITORY")
	if err != nil {
		return err
	}
	if ref := env["GITHUB_REF"]; !*dryRun && ref != "refs/heads/"+*branch {
		return fmt.Errorf("正式发版（dry_run=false）只在 %s 上跑，这次是 %s；别的分支用 dry_run", *branch, ref)
	}
	p, err := planRelease(env["GITHUB_REPOSITORY"], env["GITHUB_SHA"], *bump, *dryRun)
	if err != nil {
		return err
	}
	fmt.Fprintf(os.Stderr, "发版 v%s（dry_run=%t）：%s；versionCode %s\n", p.version, *dryRun, p.note, p.versionCode)
	fmt.Printf("version=%s\ntag=v%s\nversion_code=%s\n", p.version, p.version, p.versionCode)
	return nil
}
