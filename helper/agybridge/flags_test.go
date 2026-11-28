package agybridge

import (
	"slices"
	"testing"
)

func TestParseArgsAndBuildACPArgs(t *testing.T) {
	opts := parseArgs([]string{
		"--cwd", "/work/app",
		"--agent", "reviewer",
		"--add-dir", "/extra/one",
		"--add-dir", "/extra/two with space",
		"--sandbox",
	})
	if opts.agent != "reviewer" || !opts.sandbox || len(opts.extraDirs) != 2 {
		t.Fatalf("unexpected opts: %+v", opts)
	}
	got := buildACPArgs(opts.extraDirs, opts.agent, opts.sandbox)
	want := []string{
		"acp", "--process-per-session",
		"--add-dir", "/extra/one",
		"--add-dir", "/extra/two with space",
		"--agent", "reviewer",
		"--sandbox",
	}
	if !slices.Equal(got, want) {
		t.Fatalf("buildACPArgs = %v, want %v", got, want)
	}
}
