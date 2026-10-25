package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/silmin/cdj-music-link/tui/internal/model"
)

const playerJSON = `{"number":1,"name":"fake-cdj-1","address":"172.21.0.2","updatedAt":1792905297307,"playState":"playing","isPlaying":true,"isPaused":false,"isCued":false,"isLooping":false,"isOnAir":null,"isSynced":false,"isTempoMaster":false,"trackBpm":128,"pitch":0,"tempo":128,"beatNumber":24,"beatWithinBar":4,"position":{"ms":7471,"remainingMs":348509,"updatedAt":1792905297010,"definitive":true},"track":{"id":308,"sourcePlayer":4,"slot":"usb","type":"rekordbox","title":"T","artist":null,"album":null,"key":"Dbm","genre":null,"label":null,"remixer":null,"originalArtist":null,"comment":null,"durationSec":355,"startingBpm":178,"rating":0,"year":null,"dateAdded":"2023-10-16","color":{"name":"No Color","hex":"#000000"},"artworkAvailable":true,"waveformAvailable":true,"beatgridAvailable":true}}`

const emptyPlayerJSON = `{"number":2,"name":"fake-cdj-2","address":"172.21.0.3","updatedAt":1792905297307,"playState":"empty","isPlaying":false,"isPaused":false,"isCued":false,"isLooping":false,"isOnAir":null,"isSynced":false,"isTempoMaster":false,"trackBpm":null,"pitch":0,"tempo":null,"beatNumber":null,"beatWithinBar":null,"position":null,"track":null}`

func fakeServer(t *testing.T, routes map[string]string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, ok := routes[r.URL.Path]
		if !ok {
			w.WriteHeader(http.StatusNotFound)
			fmt.Fprint(w, `{"error":"no such thing"}`)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestNewClientTrimsSlash(t *testing.T) {
	if c := NewClient("http://h:17080/"); c.Base != "http://h:17080" {
		t.Fatalf("base %q", c.Base)
	}
}

func TestRESTEndpoints(t *testing.T) {
	srv := fakeServer(t, map[string]string{
		"/v1/health":                     `{"online":true,"deviceNumber":5,"deviceCount":2,"serverTime":1,"version":"1.0.0"}`,
		"/v1/devices":                    `[{"number":1,"name":"fake-cdj-1","address":"172.21.0.2","kind":"player"}]`,
		"/v1/players":                    `[` + playerJSON + `,` + emptyPlayerJSON + `]`,
		"/v1/master":                     `{"player":null,"tempo":null}`,
		"/v1/now-playing":                playerJSON,
		"/v1/players/1/waveform/preview": `{"style":"blue","segments":4,"maxHeight":31,"heights":[0,5,31,2],"colors":["#000000","#000000","#000000","#000000"]}`,
	})
	c := NewClient(srv.URL + "/")

	h, err := c.Health()
	if err != nil || !h.Online || h.DeviceNumber == nil || *h.DeviceNumber != 5 {
		t.Fatalf("health %+v %v", h, err)
	}
	ds, err := c.Devices()
	if err != nil || len(ds) != 1 || ds[0].Kind != "player" {
		t.Fatalf("devices %+v %v", ds, err)
	}
	ps, err := c.Players()
	if err != nil || len(ps) != 2 || ps[1].Track != nil || ps[1].Position != nil || ps[0].Track.ID != 308 {
		t.Fatalf("players %+v %v", ps, err)
	}
	m, err := c.Master()
	if err != nil || m.Player != nil || m.Tempo != nil {
		t.Fatalf("master %+v %v", m, err)
	}
	np, err := c.NowPlaying()
	if err != nil || np == nil || np.Number != 1 {
		t.Fatalf("now-playing %+v %v", np, err)
	}
	w, err := c.WaveformPreview(1)
	if err != nil || w.MaxHeight != 31 || len(w.Heights) != 4 || w.Heights[2] != 31 {
		t.Fatalf("waveform %+v %v", w, err)
	}

	snap, err := c.Snapshot()
	if err != nil || !snap.Online || *snap.DeviceNumber != 5 || len(snap.Players) != 2 || len(snap.Devices) != 1 || snap.NowPlaying == nil || snap.Master.Player != nil {
		t.Fatalf("snapshot %+v %v", snap, err)
	}
}

func TestNowPlayingNull(t *testing.T) {
	srv := fakeServer(t, map[string]string{"/v1/now-playing": "null"})
	np, err := NewClient(srv.URL).NowPlaying()
	if err != nil || np != nil {
		t.Fatalf("null body must give nil, nil: %+v %v", np, err)
	}
}

func TestStatusError(t *testing.T) {
	srv := fakeServer(t, map[string]string{})
	_, err := NewClient(srv.URL).WaveformPreview(3)
	var se *StatusError
	if !errors.As(err, &se) || se.Code != 404 || se.Message != "no such thing" || !IsNotFound(err) {
		t.Fatalf("err %v", err)
	}
	if IsNotFound(errors.New("x")) {
		t.Fatal("plain error is not 404")
	}
}

func TestBadJSONIsError(t *testing.T) {
	srv := fakeServer(t, map[string]string{"/v1/players": `[{"number":`})
	if _, err := NewClient(srv.URL).Players(); err == nil {
		t.Fatal("expected decode error")
	}
}

func TestStream(t *testing.T) {
	frames := "retry: 2000\nevent: snapshot\nid: 1\ndata: {\"players\":[" + emptyPlayerJSON + "],\"master\":{\"player\":null,\"tempo\":null},\"devices\":[],\"online\":true,\"serverTime\":1}\n\n" +
		": comment\r\n\r\n" +
		"event: player\r\nid: 2\r\ndata: " + playerJSON + "\r\n\r\n" +
		"event: beat\nid: 3\ndata: {\"player\":1,\"beatNumber\":null,\"beatWithinBar\":null,\"tempo\":null,\n" +
		"data: \"nextBeatInMs\":null,\"nextBarInMs\":null,\"isTempoMaster\":false,\"position\":null,\"waveform\":null,\"receivedAt\":1}\n\n" +
		"event: track\nid: 4\ndata: {\"player\":1,\"track\":null}\n\n" +
		"event: future\nid: 5\ndata: {}\n\n" +
		"event: player\nid: 6\ndata: {broken\n\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/stream" || r.Header.Get("Accept") != "text/event-stream" {
			http.Error(w, "bad", 400)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, frames)
	}))
	defer srv.Close()

	ch, err := NewClient(srv.URL).Stream(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	var got []model.Event
	for e := range ch { // closes when the server ends the response
		got = append(got, e)
	}
	if len(got) != 6 {
		t.Fatalf("events: %d %+v", len(got), got)
	}
	wantKinds := []model.EventKind{model.EventSnapshot, model.EventPlayer, model.EventBeat, model.EventTrack, model.EventInvalid, model.EventInvalid}
	for i, k := range wantKinds {
		if got[i].Kind != k || got[i].At.IsZero() {
			t.Fatalf("event %d: kind %v want %v (%+v)", i, got[i].Kind, k, got[i])
		}
	}
	if got[0].ID != "1" || len(got[0].Snapshot.Players) != 1 {
		t.Fatalf("snapshot %+v", got[0])
	}
	if got[1].Player.Number != 1 || got[1].Player.Track == nil || got[1].ID != "2" {
		t.Fatalf("flat player %+v", got[1].Player)
	}
	if got[2].Beat.Position != nil || got[2].Beat.Player != 1 {
		t.Fatalf("beat %+v", got[2].Beat)
	}
	if got[3].Track.Track != nil {
		t.Fatal("track null")
	}
	if got[4].Name != "future" || got[4].Err == nil || got[5].Name != "player" || got[5].Err == nil || got[5].ID != "6" {
		t.Fatalf("invalid events %+v %+v", got[4], got[5])
	}
}

func TestStreamNon200(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(503)
	}))
	defer srv.Close()
	if _, err := NewClient(srv.URL).Stream(context.Background()); err == nil {
		t.Fatal("expected error")
	}
}

func TestStreamCancelCloses(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "event: online\ndata: {\"online\":true}\n\n")
		w.(http.Flusher).Flush()
		select {
		case <-r.Context().Done():
		case <-release:
		}
	}))
	defer srv.Close()
	defer close(release)

	ctx, cancel := context.WithCancel(context.Background())
	ch, err := NewClient(srv.URL).Stream(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if e := <-ch; e.Kind != model.EventOnline {
		t.Fatalf("first %+v", e)
	}
	cancel()
	select {
	case _, ok := <-ch:
		if ok {
			t.Fatal("no more events expected")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("channel not closed after cancel")
	}
}

func TestStreamNotSubjectToRESTTimeout(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.(http.Flusher).Flush()
		time.Sleep(300 * time.Millisecond)
		fmt.Fprint(w, "event: online\ndata: {\"online\":true}\n\n")
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	c.HTTP.Timeout = 100 * time.Millisecond
	ch, err := c.Stream(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if e, ok := <-ch; !ok || e.Kind != model.EventOnline {
		t.Fatalf("stream cut by REST timeout: %+v %v", e, ok)
	}
}
