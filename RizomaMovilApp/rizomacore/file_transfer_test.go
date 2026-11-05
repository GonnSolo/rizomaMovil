package rizomacore

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// buildStream stages data and returns the wire messages (offer, chunks..., end) the sender would emit.
func buildStream(t *testing.T, filename string, data []byte) []FileMessage {
	t.Helper()
	header, processed, err := stageLocalAttachment(filename, "application/octet-stream", "", data, false)
	if err != nil {
		t.Fatalf("stage: %v", err)
	}
	out := make(chan string, header.TotalChunks+2)
	if err := SendAttachmentWithHeader(header, processed, out); err != nil {
		t.Fatalf("send: %v", err)
	}
	close(out)
	var msgs []FileMessage
	for raw := range out {
		var m FileMessage
		if err := json.Unmarshal([]byte(raw), &m); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		msgs = append(msgs, m)
	}
	return msgs
}

func randomBytes(t *testing.T, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return b
}

func useTempDownloadDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	prev := GetDownloadDirectory()
	SetDownloadDirectory(dir)
	t.Cleanup(func() { SetDownloadDirectory(prev) })
	return dir
}

func deliver(t *testing.T, peer string, msgs []FileMessage, transfers map[string]*FileTransferState) *FileTransferState {
	t.Helper()
	var last *FileTransferState
	for _, m := range msgs {
		st, err := receiveFileChunkFrom(peer, m, transfers)
		if err != nil {
			t.Fatalf("%s #%d: unexpected error: %v", m.Type, m.ChunkNum, err)
		}
		last = st
	}
	return last
}

func TestRoundTripSavesVerifiedFile(t *testing.T) {
	useTempDownloadDir(t)
	data := randomBytes(t, 3*FileChunkSize+123)
	msgs := buildStream(t, "doc.bin", data)

	transfers := map[string]*FileTransferState{}
	st := deliver(t, "peerA", msgs, transfers)
	if st == nil || !st.Completed {
		t.Fatalf("transfer not completed: %+v", st)
	}
	got, err := os.ReadFile(st.SavedPath)
	if err != nil || !bytes.Equal(got, data) {
		t.Fatalf("saved content mismatch (err=%v)", err)
	}
	if len(transfers) != 0 {
		t.Fatalf("transfer state not cleaned up: %d left", len(transfers))
	}
}

func TestEarlyFileEndIsNotAnError(t *testing.T) {
	useTempDownloadDir(t)
	msgs := buildStream(t, "late.bin", randomBytes(t, 2*FileChunkSize+5))
	// Reorder: offer, chunk0, chunk1, END, chunk2
	offer, chunks, end := msgs[0], msgs[1:len(msgs)-1], msgs[len(msgs)-1]
	order := []FileMessage{offer, chunks[0], chunks[1], end, chunks[2]}

	transfers := map[string]*FileTransferState{}
	for i, m := range order {
		st, err := receiveFileChunkFrom("p", m, transfers)
		if err != nil {
			t.Fatalf("step %d (%s): unexpected error %v", i, m.Type, err)
		}
		if m.Type == "file_end" && st.Completed {
			t.Fatalf("completed before last chunk arrived")
		}
		if i == len(order)-1 && !st.Completed {
			t.Fatalf("last chunk should complete the transfer")
		}
	}
}

func TestDuplicateChunksAreIdempotent(t *testing.T) {
	useTempDownloadDir(t)
	data := randomBytes(t, 2*FileChunkSize)
	msgs := buildStream(t, "dup.bin", data)
	withDup := append([]FileMessage{msgs[0], msgs[1], msgs[1]}, msgs[2:]...)

	transfers := map[string]*FileTransferState{}
	st := deliver(t, "p", withDup, transfers)
	if !st.Completed {
		t.Fatal("transfer with duplicate chunk did not complete")
	}
	got, _ := os.ReadFile(st.SavedPath)
	if !bytes.Equal(got, data) {
		t.Fatal("duplicate chunk corrupted the file")
	}
}

func TestOfferValidation(t *testing.T) {
	valid := buildStream(t, "ok.bin", randomBytes(t, 100))[0]

	cases := map[string]func(m *FileMessage){
		"no checksum":  func(m *FileMessage) { m.Checksum = "" },
		"bad checksum": func(m *FileMessage) { m.Checksum = "zz" },
		"zero size":    func(m *FileMessage) { m.FileSize = 0; m.TotalChunks = 0 },
		"over max size": func(m *FileMessage) {
			m.FileSize = MaxFileTransferBytes + 1
			m.TotalChunks = expectedChunkCount(m.FileSize)
		},
		"wrong chunks":     func(m *FileMessage) { m.TotalChunks = 999 },
		"zero chunks hack": func(m *FileMessage) { m.TotalChunks = 0 },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			m := valid
			mutate(&m)
			_, err := receiveFileChunkFrom("p", m, map[string]*FileTransferState{})
			if !errors.Is(err, errTransferRejected) {
				t.Fatalf("expected rejection, got %v", err)
			}
		})
	}
}

func TestOversizedChunkAbortsTransfer(t *testing.T) {
	useTempDownloadDir(t)
	msgs := buildStream(t, "big.bin", randomBytes(t, FileChunkSize))
	transfers := map[string]*FileTransferState{}
	if _, err := receiveFileChunkFrom("p", msgs[0], transfers); err != nil {
		t.Fatal(err)
	}
	evil := msgs[1]
	evil.Data = msgs[1].Data + msgs[1].Data[:8] // decodes to more than FileChunkSize
	_, err := receiveFileChunkFrom("p", evil, transfers)
	if !errors.Is(err, errTransferFailed) {
		t.Fatalf("expected transfer failure, got %v", err)
	}
	if len(transfers) != 0 {
		t.Fatal("aborted transfer should be discarded")
	}
}

func TestChecksumMismatchIsReported(t *testing.T) {
	dir := useTempDownloadDir(t)
	msgs := buildStream(t, "tamper.bin", randomBytes(t, FileChunkSize+10))
	tampered := buildStream(t, "tamper.bin", randomBytes(t, FileChunkSize+10))
	// Same offer/end, but chunk data from a different file.
	msgs[1].Data, msgs[2].Data = tampered[1].Data, tampered[2].Data

	transfers := map[string]*FileTransferState{}
	var err error
	for _, m := range msgs {
		_, err = receiveFileChunkFrom("p", m, transfers)
	}
	if !errors.Is(err, errTransferFailed) {
		t.Fatalf("expected checksum failure, got %v", err)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 0 {
		t.Fatalf("corrupt file or .part left on disk: %v", entries)
	}
}

func TestTransfersAreScopedPerPeer(t *testing.T) {
	useTempDownloadDir(t)
	msgs := buildStream(t, "same.bin", randomBytes(t, FileChunkSize+1))
	transfers := map[string]*FileTransferState{}
	if _, err := receiveFileChunkFrom("peerA", msgs[0], transfers); err != nil {
		t.Fatal(err)
	}
	// peerB cannot inject chunks into peerA's transfer.
	if _, err := receiveFileChunkFrom("peerB", msgs[1], transfers); err == nil {
		t.Fatal("chunk from another peer was accepted")
	}
}

func TestConcurrentTransferLimit(t *testing.T) {
	transfers := map[string]*FileTransferState{}
	for i := 0; i < MaxConcurrentTransfers; i++ {
		offer := buildStream(t, "f.bin", randomBytes(t, 10))[0]
		if _, err := receiveFileChunkFrom("p", offer, transfers); err != nil {
			t.Fatalf("offer %d: %v", i, err)
		}
	}
	extra := buildStream(t, "f.bin", randomBytes(t, 10))[0]
	if _, err := receiveFileChunkFrom("p", extra, transfers); !errors.Is(err, errTransferRejected) {
		t.Fatalf("expected rejection over limit, got %v", err)
	}
}

func TestSanitizeFilenameBlocksTraversal(t *testing.T) {
	for in, want := range map[string]string{
		"../../secret_dir/camera_photo.jpg": "camera_photo.jpg",
		`..\..\windows\evil.exe`:            "evil.exe",
		"normal.png":                        "normal.png",
	} {
		if got := sanitizeFilename(in); got != want {
			t.Errorf("sanitizeFilename(%q) = %q, want %q", in, got, want)
		}
	}
	if got := sanitizeFilename("../"); filepath.Base(got) != got || got == "" {
		t.Errorf("sanitizeFilename(\"../\") produced unsafe name %q", got)
	}
}

func TestSendFileBytesDoesNotKeepLocalCopy(t *testing.T) {
	dir := useTempDownloadDir(t)
	out := make(chan string, 8)
	if err := SendFileFromBytes("x.bin", randomBytes(t, 100), out); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "sent")); !os.IsNotExist(err) {
		t.Fatal("legacy entrypoint should not create a sent/ copy")
	}
}
