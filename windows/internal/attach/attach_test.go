package attach

import (
	"bytes"
	"encoding/base64"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"testing"
)

func pngOf(t *testing.T, w, h int, fill color.Color) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			img.Set(x, y, fill)
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestEncodeImageScalesToMaxEdge(t *testing.T) {
	im, raw, err := EncodeImage(pngOf(t, 3136, 1000, color.NRGBA{R: 200, A: 255}))
	if err != nil {
		t.Fatal(err)
	}
	if im.MediaType != "image/jpeg" {
		t.Errorf("MediaType = %q", im.MediaType)
	}
	data, err := base64.StdEncoding.DecodeString(im.Data)
	if err != nil || !bytes.Equal(data, raw) {
		t.Fatalf("Data 不是返回的 JPEG 的 base64：%v", err)
	}
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(raw))
	if err != nil || cfg.Width != MaxEdge || cfg.Height != 500 {
		t.Errorf("缩成了 %dx%d（%v），应是 %dx500", cfg.Width, cfg.Height, err, MaxEdge)
	}
}

func TestEncodeImageFlattensTransparencyOnWhite(t *testing.T) {
	_, raw, err := EncodeImage(pngOf(t, 10, 10, color.NRGBA{}))
	if err != nil {
		t.Fatal(err)
	}
	img, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	if r, g, b, _ := img.At(5, 5).RGBA(); r>>8 < 250 || g>>8 < 250 || b>>8 < 250 {
		t.Errorf("透明的地方是 %d,%d,%d，应铺白", r>>8, g>>8, b>>8)
	}
}

func TestEncodeImageRejectsOtherFiles(t *testing.T) {
	if _, _, err := EncodeImage([]byte("%PDF-1.7")); err != ErrNotImage {
		t.Errorf("err = %v, want ErrNotImage", err)
	}
}

func TestFitWithin(t *testing.T) {
	for _, c := range []struct{ w, h, edge, ww, wh int }{
		{800, 600, 1568, 800, 600},
		{4000, 3000, 1568, 1568, 1176},
		{1, 5000, 1568, 1, 1568},
	} {
		if w, h := FitWithin(c.w, c.h, c.edge); w != c.ww || h != c.wh {
			t.Errorf("FitWithin(%d,%d) = %d,%d; want %d,%d", c.w, c.h, w, h, c.ww, c.wh)
		}
	}
}

func TestLabelsAndSizes(t *testing.T) {
	for n, want := range map[int64]string{999: "999 B", 1500: "1.5 KB", 12_345_678: "12.3 MB", 200_000_000: "200 MB", 999_600: "1 MB"} {
		if got := FormatBytes(n); got != want {
			t.Errorf("FormatBytes(%d) = %q, want %q", n, got, want)
		}
	}
	if Label("a.zip", 9_999_999) != "a.zip" || Label("a.zip", 25_000_000) != "a.zip · 25 MB" || Label("a.zip", -1) != "a.zip" {
		t.Error("Label")
	}
	if !TooLarge(MaxBytes+1) || TooLarge(-1) {
		t.Error("TooLarge")
	}
	if got := TooLargeReason("录像.mp4", 300_000_000); got != "「录像.mp4」有 300 MB，超过 200 MB 的上限，没有传上去" {
		t.Errorf("TooLargeReason = %q", got)
	}
	if !IsImage("A.JPG") || IsImage("a.svg") || IsImage("png") {
		t.Error("IsImage")
	}
}
