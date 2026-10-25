package api

import (
	"bufio"
	"io"
	"strconv"
	"strings"
	"time"
)

// Frame is one dispatched SSE event (WHATWG EventSource parsing rules).
type Frame struct {
	Event string        // "message" when no event: field was given
	Data  string        // data: lines joined with "\n"
	ID    string        // last event id seen on the stream so far
	Retry time.Duration // last retry: seen on the stream so far (0 = none)
}

// sseReader parses text/event-stream. Lines end in LF or CRLF (a lone CR is
// not treated as a line end). Lines may be arbitrarily long.
type sseReader struct {
	r      *bufio.Reader
	LastID string
	Retry  time.Duration
}

func newSSEReader(r io.Reader) *sseReader {
	return &sseReader{r: bufio.NewReaderSize(r, 64*1024)}
}

// Next returns the next dispatched frame. At EOF a partial frame (no
// terminating blank line) is discarded and io.EOF is returned.
func (s *sseReader) Next() (Frame, error) {
	var event string
	var data strings.Builder
	for {
		line, err := s.r.ReadString('\n')
		if err != nil {
			if err == io.EOF {
				return Frame{}, io.EOF
			}
			return Frame{}, err
		}
		line = strings.TrimSuffix(strings.TrimSuffix(line, "\n"), "\r")
		if line == "" {
			if data.Len() == 0 {
				event = ""
				continue
			}
			d := strings.TrimSuffix(data.String(), "\n")
			if event == "" {
				event = "message"
			}
			return Frame{Event: event, Data: d, ID: s.LastID, Retry: s.Retry}, nil
		}
		if line[0] == ':' {
			continue
		}
		field, value, found := strings.Cut(line, ":")
		if found {
			value = strings.TrimPrefix(value, " ")
		}
		switch field {
		case "event":
			event = value
		case "data":
			data.WriteString(value)
			data.WriteByte('\n')
		case "id":
			if !strings.ContainsRune(value, 0) {
				s.LastID = value
			}
		case "retry":
			if ms, err := strconv.ParseUint(value, 10, 63); err == nil { // digits only
				s.Retry = time.Duration(ms) * time.Millisecond
			}
		}
	}
}
