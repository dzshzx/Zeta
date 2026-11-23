package main

import (
	"errors"
	"strings"
	"testing"
)

func TestReleaseVersion(t *testing.T) {
	tags := []string{"v0.6.9", "v0.7.0", "v0.7.10", "v0.7", "v0.8.0-rc1", "latest"}
	none := func() ([]string, error) {
		t.Error("published Releases are read only for a tagged commit")
		return nil, nil
	}
	cases := []struct {
		name      string
		tags      []string
		headTags  []string
		published []string
		bump      string
		dryRun    bool
		want      string
		err       string
	}{
		{"patch bumps the highest release tag", tags, nil, nil, "patch", false, "0.7.11", ""},
		{"minor resets the patch", tags, nil, nil, "minor", false, "0.8.0", ""},
		{"major resets the rest", tags, nil, nil, "major", false, "1.0.0", ""},
		{"versions compare as numbers", []string{"v0.9.0", "v0.10.0"}, nil, nil, "patch", false, "0.10.1", ""},
		{"a tag without a published Release resumes", tags, []string{"v0.7.11"}, []string{"v0.7.10"}, "minor", false, "0.7.11", ""},
		{"a released commit refuses a release", tags, []string{"v0.7.10"}, []string{"v0.7.10"}, "patch", false, "", "已发过版"},
		{"a dry run on a released commit previews the bump", tags, []string{"v0.7.10"}, []string{"v0.7.10"}, "patch", true, "0.7.11", ""},
		{"other tags on the commit do not count", tags, []string{"latest", "v0.8.0-rc1"}, nil, "patch", false, "0.7.11", ""},
		{"no release tag has no baseline", []string{"v1.0", "x"}, nil, nil, "patch", false, "", "不知道从哪个版本升"},
		{"an unknown bump fails", tags, nil, nil, "pico", false, "", "不认识的 bump"},
	}
	for _, c := range cases {
		published := none
		if c.published != nil {
			published = func() ([]string, error) { return c.published, nil }
		}
		got, _, err := releaseVersion(c.tags, c.headTags, published, c.bump, c.dryRun)
		if got != c.want || (c.err == "") != (err == nil) || (err != nil && !strings.Contains(err.Error(), c.err)) {
			t.Errorf("%s: %q, %v", c.name, got, err)
		}
	}
	failing := func() ([]string, error) { return nil, errors.New("offline") }
	if _, _, err := releaseVersion(tags, []string{"v0.7.10"}, failing, "patch", true); err == nil {
		t.Error("an unreadable Release list fails")
	}
}

func TestPreviousTag(t *testing.T) {
	tags := []string{"v0.7.0", "v0.7.1", "v0.7.10", "v0.7.2-rc1"}
	cases := map[string]string{"0.7.2": "v0.7.1", "0.7.10": "v0.7.1", "0.7.11": "v0.7.10", "0.8.0": "v0.7.10", "0.7.0": ""}
	for version, want := range cases {
		if got, err := previousTag(tags, version); got != want || err != nil {
			t.Errorf("before %s: %q, %v", version, got, err)
		}
	}
	if _, err := previousTag(tags, "0.7.2-dev.1"); err == nil {
		t.Error("a prerelease is not a release version")
	}
}

func TestRunNextVersion(t *testing.T) {
	for name, value := range map[string]string{"GITHUB_REF": "refs/heads/master", "GITHUB_SHA": "c0ffee",
		"GITHUB_REPOSITORY": "o/r"} {
		t.Setenv(name, value)
	}
	answers := map[string]string{
		"git tag --list":       "v0.7.0\nv0.6.9",
		"git tag --points-at":  "",
		"git rev-list --count": "321",
	}
	fakeCommands(t, answers)
	out := captureStdout(t, func() error { return runNextVersion([]string{"-bump", "patch", "-branch", "master"}) })
	if out != "version=0.7.1\ntag=v0.7.1\nversion_code=321\n" {
		t.Errorf("outputs %q", out)
	}
	t.Setenv("GITHUB_REF", "refs/heads/task/x")
	if err := runNextVersion([]string{"-bump", "patch", "-branch", "master"}); err == nil || !strings.Contains(err.Error(), "只在 master") {
		t.Errorf("a release off the default branch fails: %v", err)
	}
}
