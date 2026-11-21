package main

import (
	"flag"
	"fmt"
	"path/filepath"
	"strings"
)

// runPublish 在 GitHub Actions 里把 package 写出的资产发布成这个提交的 Release。
// 顺序：删同名的旧草稿 → 建含全部资产的草稿 → 经 API 在本提交上打附注 tag → 发布并设为最新。
// 任何一步失败，修好后重跑整个发版：next-version 认出带 tag 而 Release 没发布的提交，沿用这个版本续发。
func runPublish(args []string) error {
	fs := flag.NewFlagSet("publish", flag.ContinueOnError)
	version := fs.String("version", "", "这次的版本 X.Y.Z")
	assets := fs.String("assets", "", "package 写出资产的目录")
	if err := parse(fs, args, "version", "assets"); err != nil {
		return err
	}
	env, err := actionsEnv("GITHUB_SHA", "GITHUB_REPOSITORY")
	if err != nil {
		return err
	}
	u, err := checkAssets(*assets, *version)
	if err != nil {
		return err
	}
	sha, repo := env["GITHUB_SHA"], env["GITHUB_REPOSITORY"]
	if u.SourceCommit != sha {
		return fmt.Errorf("资产出自 %s，不是这次的 %s", u.SourceCommit, sha)
	}
	var files []string
	for _, name := range assetNames(*version) {
		files = append(files, filepath.Join(*assets, name))
	}
	if err := publishRelease(repo, u.Tag, sha, files, releaseBody(u)); err != nil {
		return err
	}
	done := fmt.Sprintf("\n已发布 %s：https://github.com/%s/releases/tag/%s\n", u.Tag, repo, u.Tag)
	fmt.Print(done)
	return stepSummary(done)
}

func publishRelease(repo, tag, sha string, files []string, body string) error {
	if err := deleteDrafts(repo, tag); err != nil {
		return err
	}
	create := append([]string{"release", "create", tag}, files...)
	create = append(create, "--repo", repo, "--draft", "--target", sha, "--title", "Zeta "+strings.TrimPrefix(tag, "v"), "--notes", body)
	if _, err := gh(create...); err != nil {
		return err
	}
	if err := tagCommit(repo, tag, sha); err != nil {
		return err
	}
	_, err := gh("release", "edit", tag, "--repo", repo, "--verify-tag", "--draft=false", "--latest")
	return err
}

// deleteDrafts 删掉上次没发完留下的同名草稿（草稿还没有 tag，外面看不到）。
func deleteDrafts(repo, tag string) error {
	out, err := gh("api", "repos/"+repo+"/releases?per_page=100", "--paginate",
		"--jq", ".[] | select(.draft) | [.id, .tag_name] | @tsv")
	if err != nil {
		return err
	}
	for _, line := range strings.Split(out, "\n") {
		id, name, ok := strings.Cut(strings.TrimSpace(line), "\t")
		if !ok || name != tag {
			continue
		}
		if _, err := gh("api", "-X", "DELETE", "repos/"+repo+"/releases/"+id); err != nil {
			return err
		}
		fmt.Printf("删掉了 %s 的旧草稿 %s\n", tag, id)
	}
	return nil
}

// tagCommit 经 API 在 sha 上打附注 tag；tag 已指向 sha 就不动，指向别的提交就失败（推上去的 tag 不改）。
func tagCommit(repo, tag, sha string) error {
	api := "repos/" + repo
	out, err := gh("api", api+"/git/matching-refs/tags/"+tag, "--jq", ".[] | [.ref, .object.type, .object.sha] | @tsv")
	if err != nil {
		return err
	}
	for _, line := range strings.Split(out, "\n") {
		fields := strings.Split(strings.TrimSpace(line), "\t")
		if len(fields) != 3 || fields[0] != "refs/tags/"+tag {
			continue
		}
		commit := fields[2]
		if fields[1] == "tag" {
			if commit, err = gh("api", api+"/git/tags/"+fields[2], "--jq", ".object.sha"); err != nil {
				return err
			}
		}
		if commit != sha {
			return fmt.Errorf("tag %s 已经指向 %s，不是这次的 %s", tag, commit, sha)
		}
		fmt.Printf("tag %s 已经指向这个提交\n", tag)
		return nil
	}
	object, err := gh("api", api+"/git/tags", "-f", "tag="+tag, "-f", "message=Zeta "+tag,
		"-f", "object="+sha, "-f", "type=commit", "--jq", ".sha")
	if err != nil {
		return err
	}
	_, err = gh("api", api+"/git/refs", "-f", "ref=refs/tags/"+tag, "-f", "sha="+object, "--silent")
	return err
}
