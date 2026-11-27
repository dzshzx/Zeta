package appversion

import "testing"

func TestVersion(t *testing.T) {
	for _, tc := range []struct {
		code, name string
		want       Version
		release    bool
	}{
		{"812", "0.7.6", Version{812, "0.7.6"}, true},
		{"815", "0.7.7-dev.3+gabc1234", Version{815, "0.7.7-dev.3+gabc1234"}, false},
		{"", "", Version{}, false}, // go run、go test 编的
		{"abc", "0.7.6", Version{}, false},
		{"0", "0.7.6", Version{}, false},
		{"812", "", Version{}, false},
		{"812", "v0.7.6", Version{812, "v0.7.6"}, false},
	} {
		v := parseVersion(tc.code, tc.name)
		if v != tc.want || v.Release() != tc.release {
			t.Errorf("parseVersion(%q, %q) = %+v release %v, want %+v release %v", tc.code, tc.name, v, v.Release(), tc.want, tc.release)
		}
	}
	if Current() != (Version{}) {
		t.Errorf("a test build is %+v, want a development build", Current())
	}
	// 正式版的名字不能变（设置目录、下载目录都按它）；别的构建都是测试版
	if got := (Version{812, "0.7.6"}).AppName(); got != "Zeta" {
		t.Errorf("release AppName %q", got)
	}
	for _, v := range []Version{{815, "0.7.7-dev.3+gabc1234"}, {}} {
		if got := v.AppName(); got != "Zeta Dev" {
			t.Errorf("%+v: AppName %q", v, got)
		}
	}
}
