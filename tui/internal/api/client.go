// Package api is a client for the cdj-music-link HTTP/SSE API (/v1/*).
package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/silmin/cdj-music-link/tui/internal/model"
)

// Client talks to one cdj-music-link server.
type Client struct {
	Base       string       // e.g. http://localhost:17080, no trailing slash
	HTTP       *http.Client // REST calls (has a timeout)
	StreamHTTP *http.Client // /v1/stream (no timeout; cancel via ctx)
}

// NewClient returns a Client for base with a 5 s REST timeout.
func NewClient(base string) *Client {
	return &Client{
		Base:       strings.TrimRight(base, "/"),
		HTTP:       &http.Client{Timeout: 5 * time.Second},
		StreamHTTP: &http.Client{Transport: streamTransport()},
	}
}

// streamTransport sends TCP keep-alives every 15 s so a dead peer is noticed
// by the kernel, and bounds the wait for response headers.
func streamTransport() *http.Transport {
	t := http.DefaultTransport.(*http.Transport).Clone()
	t.DialContext = (&net.Dialer{Timeout: 5 * time.Second, KeepAlive: 15 * time.Second}).DialContext
	t.ResponseHeaderTimeout = 10 * time.Second
	return t
}

// StatusError is a non-2xx response. Message is the body's "error" field if any.
type StatusError struct {
	Path    string
	Code    int
	Message string
}

func (e *StatusError) Error() string {
	if e.Message != "" {
		return fmt.Sprintf("GET %s: %d: %s", e.Path, e.Code, e.Message)
	}
	return fmt.Sprintf("GET %s: %d", e.Path, e.Code)
}

// IsNotFound reports whether err is a 404 StatusError
// (e.g. no waveform for that player yet).
func IsNotFound(err error) bool {
	var se *StatusError
	return errors.As(err, &se) && se.Code == http.StatusNotFound
}

func (c *Client) get(path string, out any) error {
	resp, err := c.HTTP.Get(c.Base + path)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		se := &StatusError{Path: path, Code: resp.StatusCode}
		var body struct {
			Error string `json:"error"`
		}
		if b, err := io.ReadAll(io.LimitReader(resp.Body, 4096)); err == nil && json.Unmarshal(b, &body) == nil {
			se.Message = body.Error
		}
		return se
	}
	if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
		return fmt.Errorf("GET %s: decode: %w", path, err)
	}
	return nil
}

// Health is GET /v1/health.
func (c *Client) Health() (model.Health, error) {
	var h model.Health
	return h, c.get("/v1/health", &h)
}

// Devices is GET /v1/devices.
func (c *Client) Devices() ([]model.Device, error) {
	var d []model.Device
	return d, c.get("/v1/devices", &d)
}

// Players is GET /v1/players.
func (c *Client) Players() ([]model.Player, error) {
	var p []model.Player
	return p, c.get("/v1/players", &p)
}

// Master is GET /v1/master.
func (c *Client) Master() (model.MasterInfo, error) {
	var m model.MasterInfo
	return m, c.get("/v1/master", &m)
}

// NowPlaying is GET /v1/now-playing; a JSON null body gives (nil, nil).
func (c *Client) NowPlaying() (*model.Player, error) {
	var p *model.Player
	if err := c.get("/v1/now-playing", &p); err != nil {
		return nil, err
	}
	return p, nil
}

// WaveformPreview is GET /v1/players/{n}/waveform/preview. 404 when the
// player has no waveform (see IsNotFound).
func (c *Client) WaveformPreview(n int) (*model.WaveformPreview, error) {
	var w model.WaveformPreview
	if err := c.get(fmt.Sprintf("/v1/players/%d/waveform/preview", n), &w); err != nil {
		return nil, err
	}
	return &w, nil
}

// Snapshot fetches health, devices, players, master and now-playing
// (sequentially) for State.ReplaceSnapshot.
func (c *Client) Snapshot() (model.Snapshot, error) {
	var s model.Snapshot
	h, err := c.Health()
	if err != nil {
		return s, err
	}
	s.ServerTime, s.At = h.ServerTime, time.Now()
	if s.Devices, err = c.Devices(); err != nil {
		return s, err
	}
	if s.Players, err = c.Players(); err != nil {
		return s, err
	}
	if s.Master, err = c.Master(); err != nil {
		return s, err
	}
	if s.NowPlaying, err = c.NowPlaying(); err != nil {
		return s, err
	}
	s.Online, s.DeviceNumber = h.Online, h.DeviceNumber
	return s, nil
}

// Stream opens GET /v1/stream. The error is for connect/status failures only.
// Events (At = local receive time) arrive on the channel, which is closed when
// the connection ends or ctx is cancelled; the caller reconnects. Unknown event
// names and undecodable data arrive as model.EventInvalid with Name and Err set.
func (c *Client) Stream(ctx context.Context) (<-chan model.Event, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.Base+"/v1/stream", nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "text/event-stream")
	req.Header.Set("Cache-Control", "no-cache")
	resp, err := c.StreamHTTP.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, &StatusError{Path: "/v1/stream", Code: resp.StatusCode}
	}
	ch := make(chan model.Event, 64)
	go func() {
		defer close(ch)
		defer resp.Body.Close()
		r := newSSEReader(resp.Body)
		for {
			f, err := r.Next()
			if err != nil {
				return
			}
			ev, derr := model.DecodeEvent(f.Event, []byte(f.Data))
			if derr != nil {
				ev = model.Event{Kind: model.EventInvalid, Name: f.Event, Err: derr}
			}
			ev.ID, ev.At = f.ID, time.Now()
			select {
			case ch <- ev:
			case <-ctx.Done():
				return
			}
		}
	}()
	return ch, nil
}
