package api

import (
	"io"
	"strings"
	"testing"
	"time"
)

func readAll(t *testing.T, in string) ([]Frame, *sseReader) {
	t.Helper()
	r := newSSEReader(strings.NewReader(in))
	var out []Frame
	for {
		f, err := r.Next()
		if err == io.EOF {
			return out, r
		}
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, f)
	}
}

func TestSSEBasicAndRetry(t *testing.T) {
	in := "event: snapshot\nid: 1\nretry: 2000\ndata: {\"a\":1}\n\nevent: beat\nid: 42\ndata: {\"b\":2}\n\n"
	fs, r := readAll(t, in)
	if len(fs) != 2 {
		t.Fatalf("frames: %+v", fs)
	}
	if fs[0].Event != "snapshot" || fs[0].ID != "1" || fs[0].Data != `{"a":1}` || fs[0].Retry != 2*time.Second {
		t.Fatalf("frame0: %+v", fs[0])
	}
	if fs[1].Event != "beat" || fs[1].ID != "42" || fs[1].Data != `{"b":2}` || fs[1].Retry != 2*time.Second {
		t.Fatalf("frame1 (retry persists): %+v", fs[1])
	}
	if r.Retry != 2*time.Second || r.LastID != "42" {
		t.Fatalf("reader state: %v %q", r.Retry, r.LastID)
	}
}

func TestSSEMultilineDataAndCRLF(t *testing.T) {
	in := "event: player\r\ndata: {\"number\":1,\r\ndata:\"name\":\"x\"}\r\n\r\n"
	fs, _ := readAll(t, in)
	if len(fs) != 1 || fs[0].Event != "player" || fs[0].Data != "{\"number\":1,\n\"name\":\"x\"}" {
		t.Fatalf("frames: %q", fs)
	}
}

func TestSSECommentsFieldsAndDefaults(t *testing.T) {
	in := ": keepalive\n\n" + // comment only: no dispatch
		"event: x\n\n" + // no data: no dispatch, buffers reset
		"data: plain\n\n" + // default event name, and previous "x" must not leak
		"retry: abc\nunknown: 1\ndata\ndata:  two spaces\n\n" + // bad retry ignored; "data" without colon = empty line
		"id: 7\ndata: last\n" // no terminating blank line at EOF: discarded
	fs, r := readAll(t, in)
	if len(fs) != 2 {
		t.Fatalf("frames: %q", fs)
	}
	if fs[0].Event != "message" || fs[0].Data != "plain" {
		t.Fatalf("frame0: %+v", fs[0])
	}
	if fs[1].Data != "\n two spaces" || r.Retry != 0 {
		t.Fatalf("frame1: %q retry=%v", fs[1].Data, r.Retry)
	}
}

func TestSSELongLine(t *testing.T) {
	big := strings.Repeat("x", 200_000) // larger than bufio.Scanner's default limit
	fs, _ := readAll(t, "data: "+big+"\n\n")
	if len(fs) != 1 || len(fs[0].Data) != len(big) {
		t.Fatal("long data line must survive")
	}
}
