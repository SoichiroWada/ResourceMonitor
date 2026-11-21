package buffer

import (
	"errors"
	"testing"
)

func TestPushTrimsOldest(t *testing.T) {
	b, _ := New(t.TempDir(), 2)
	for _, s := range []string{"a", "b", "c"} {
		if err := b.Push([]byte(s)); err != nil {
			t.Fatal(err)
		}
	}
	var got []string
	_, _ = b.Flush(10, func(p []byte) error { got = append(got, string(p)); return nil })
	if len(got) != 2 || got[0] != "b" || got[1] != "c" {
		t.Fatalf("got %v, want [b c]", got)
	}
	if b.Len() != 0 {
		t.Fatalf("buffer should be empty")
	}
}

func TestFlushStopsOnError(t *testing.T) {
	b, _ := New(t.TempDir(), 10)
	_ = b.Push([]byte("1"))
	_ = b.Push([]byte("2"))
	n, err := b.Flush(10, func(p []byte) error {
		if string(p) == "2" {
			return errors.New("down")
		}
		return nil
	})
	if n != 1 || err == nil || b.Len() != 1 {
		t.Fatalf("n=%d err=%v len=%d", n, err, b.Len())
	}
}
