package rizomacore

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Messaging & Attachment Constants inspired by Briar's MessagingConstants.java & ImageCompressorImpl.java
const (
	// FileChunkSize defines the size of each data chunk (16KB for smoother streaming over Tor).
	FileChunkSize = 16 * 1024

	// MaxAttachmentDimension mirrors Briar's MAX_ATTACHMENT_DIMENSION (1000px) for image attachments.
	MaxAttachmentDimension = 1000

	// MaxCompressedImageBytes defines the target maximum byte size for compressed images over Tor (256 KB).
	MaxCompressedImageBytes = 256 * 1024

	// MaxFileTransferBytes defines a safety cap for single file transfers over Tor (50 MB).
	MaxFileTransferBytes = 50 * 1024 * 1024

	// MaxConcurrentTransfers caps how many incoming transfers can be buffered in memory at once.
	MaxConcurrentTransfers = 4

	// TransferTimeout discards incoming transfers that never complete, so abandoned chunks do not stay in memory.
	TransferTimeout = 30 * time.Minute

	// Attachment States (mirroring Briar's AttachmentItem.State)
	AttachmentStateLoading   = "LOADING"
	AttachmentStateAvailable = "AVAILABLE"
	AttachmentStateMissing   = "MISSING"
	AttachmentStateError     = "ERROR"
)

// AttachmentHeader mirrors Briar's org.briarproject.briar.api.messaging.AttachmentHeader.
// It decouples attachment metadata and content-addressed identity from raw binary chunks.
type AttachmentHeader struct {
	TransferID  string `json:"transfer_id"`          // Content-addressed ID (SHA-256 prefix + timestamp)
	Filename    string `json:"filename"`             // Sanitized filename
	MimeType    string `json:"mime_type"`            // MIME type (e.g., "image/jpeg", "audio/ogg", "application/pdf")
	FileSize    int64  `json:"file_size"`            // Total size in bytes
	TotalChunks int    `json:"total_chunks"`         // Total 16KB chunks
	Checksum    string `json:"checksum"`             // Full SHA-256 hex digest
	Width       int    `json:"width,omitempty"`      // Image width in pixels (if image)
	Height      int    `json:"height,omitempty"`     // Image height in pixels (if image)
	Caption     string `json:"caption,omitempty"`    // Optional text caption linked to the attachment (Briar PrivateMessage text)
	LocalPath   string `json:"local_path,omitempty"` // Local filesystem path once staged or saved
	State       string `json:"state,omitempty"`      // LOADING, AVAILABLE, MISSING, ERROR
}

// FileMessage defines the wire structure for messages used during file and multimedia transfer operations.
// Maintains 100% backward compatibility with original Rizoma fields while adding Briar AttachmentHeader fields.
type FileMessage struct {
	Type        string `json:"type"`                  // Message category: "file_offer", "file_accept", "file_chunk", "file_end", "file_error", "file_cancel"
	TransferID  string `json:"transfer_id,omitempty"` // Unique content-addressed transfer ID (Briar MessageId equivalent)
	Filename    string `json:"filename"`              // Name of the file being transferred (excluding path)
	MimeType    string `json:"mime_type,omitempty"`   // MIME content type (Briar AttachmentHeader.contentType)
	FileSize    int64  `json:"file_size"`             // Total size of the file in bytes
	ChunkNum    int    `json:"chunk_num"`             // Index of the current data chunk (0-based)
	TotalChunks int    `json:"total_chunks"`          // Total number of chunks expected for the transfer
	Data        string `json:"data"`                  // Base64-encoded binary data of the current chunk
	Checksum    string `json:"checksum"`              // SHA-256 hash of the complete file for integrity verification
	Width       int    `json:"width,omitempty"`       // Optional width in px for image preview calculation (Briar ImageSizeCalculator)
	Height      int    `json:"height,omitempty"`      // Optional height in px for image preview calculation
	Caption     string `json:"caption,omitempty"`     // Optional message text associated with the attachment
}

var (
	downloadDirMu sync.Mutex
	downloadDir   = "loadingbay_in"

	// Global mutex protecting concurrent access to transfer maps across goroutines.
	transfersMu sync.Mutex
)

var (
	// errTransferRejected marks an incoming offer that was refused (invalid size, checksum, or too many transfers).
	errTransferRejected = errors.New("transfer rejected")
	// errTransferFailed marks a transfer that was aborted after starting (bad chunk, size overflow, checksum mismatch).
	errTransferFailed = errors.New("transfer failed")
)

// SetDownloadDirectory sets the directory where incoming files are stored on disk.
func SetDownloadDirectory(dir string) {
	downloadDirMu.Lock()
	defer downloadDirMu.Unlock()
	downloadDir = dir
}

// GetDownloadDirectory returns the currently configured download directory.
func GetDownloadDirectory() string {
	downloadDirMu.Lock()
	defer downloadDirMu.Unlock()
	return downloadDir
}

// FileTransferState maintains the progress, metadata, and chunk map of an active file transfer.
type FileTransferState struct {
	TransferID    string    // Unique ID of the transfer
	PeerID        string    // Peer that sent the offer (transfers are scoped per peer)
	BytesReceived int64     // Total bytes of unique chunks received so far
	StartedAt     time.Time // When the offer arrived (used to expire abandoned transfers)

	Filename    string         // Sanitized name of the file
	MimeType    string         // MIME type of the attachment
	FileSize    int64          // Total size of the file
	TotalChunks int            // Total number of chunks expected
	Checksum    string         // Expected SHA-256 checksum
	Width       int            // Image width (0 if non-image)
	Height      int            // Image height (0 if non-image)
	Caption     string         // Associated text caption
	Chunks      map[int][]byte // Mapping of chunk indices to their binary data
	Progress    int            // Number of unique chunks successfully received so far
	Accepted    bool           // Has the recipient accepted this transfer
	EndReceived bool           // Whether the "file_end" marker has arrived (handles out-of-order delivery)
	Completed   bool           // Have all chunks arrived and been verified on disk
	SavedPath   string         // Path where the file was saved
	State       string         // LOADING, AVAILABLE, ERROR
}

// sanitizeFilename prevents directory traversal vulnerabilities (e.g., "../../etc/passwd")
// by stripping directory separators and parent references.
func sanitizeFilename(name string) string {
	normalized := strings.ReplaceAll(name, "\\", "/")
	cleaned := filepath.Base(normalized)
	cleaned = strings.ReplaceAll(cleaned, "..", "")
	cleaned = strings.TrimSpace(cleaned)
	if cleaned == "" || cleaned == "." || cleaned == "/" {
		return fmt.Sprintf("attachment_%d.bin", time.Now().Unix())
	}
	return cleaned
}

// transferKey returns the canonical lookup key for a FileMessage (TransferID if present, else sanitized Filename).
func transferKey(msg FileMessage) string {
	if strings.TrimSpace(msg.TransferID) != "" {
		return msg.TransferID
	}
	return sanitizeFilename(msg.Filename)
}

// scopedTransferKey namespaces a transfer key by peer so two peers can never collide or
// inject chunks into each other's transfers.
func scopedTransferKey(peerID string, msg FileMessage) string {
	key := transferKey(msg)
	if peerID == "" {
		return key
	}
	return peerID + "|" + key
}

// expectedChunkCount returns how many FileChunkSize chunks a file of the given size must have.
func expectedChunkCount(size int64) int {
	return int((size + FileChunkSize - 1) / FileChunkSize)
}

// isSHA256Hex reports whether s is a well-formed hex-encoded SHA-256 digest.
func isSHA256Hex(s string) bool {
	if len(s) != sha256.Size*2 {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

// newTransferID builds a unique transfer ID from the content hash plus a timestamp, so sending
// the same file twice does not produce colliding transfers.
func newTransferID(checksum string) string {
	return fmt.Sprintf("%s-%x", checksum[:16], time.Now().UnixNano())
}

// purgeStaleTransfersLocked drops transfers that exceeded TransferTimeout. Caller must hold transfersMu.
func purgeStaleTransfersLocked(transfers map[string]*FileTransferState, now time.Time) {
	for key, state := range transfers {
		if now.Sub(state.StartedAt) > TransferTimeout {
			delete(transfers, key)
		}
	}
}

// DetectMimeType inspects both file extension and magic header bytes (up to 512 bytes),
// mirroring Briar's ImageHelperImpl and ContentResolver MIME resolution.
func DetectMimeType(filename string, data []byte) string {
	ext := strings.ToLower(filepath.Ext(filename))
	switch ext {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".mp3":
		return "audio/mpeg"
	case ".ogg", ".oga", ".opus":
		return "audio/ogg"
	case ".wav":
		return "audio/wav"
	case ".m4a", ".aac":
		return "audio/mp4"
	case ".mp4", ".m4v":
		return "video/mp4"
	case ".webm":
		return "video/webm"
	case ".pdf":
		return "application/pdf"
	case ".txt", ".md":
		return "text/plain"
	}

	if len(data) > 0 {
		sniffLen := len(data)
		if sniffLen > 512 {
			sniffLen = 512
		}
		detected := http.DetectContentType(data[:sniffLen])
		if idx := strings.Index(detected, ";"); idx != -1 {
			detected = strings.TrimSpace(detected[:idx])
		}
		return detected
	}

	return "application/octet-stream"
}

// extractImageDimensions decodes only the image header to obtain width and height
// (mirroring Briar's ImageSizeCalculator) without loading full pixel buffers if not needed.
func extractImageDimensions(data []byte) (int, int) {
	if len(data) == 0 {
		return 0, 0
	}
	cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return 0, 0
	}
	return cfg.Width, cfg.Height
}

// compressImageForTor implements Briar's ImageCompressorImpl strategy in Go:
//  1. If the JPEG image was already compressed on the Android Kotlin layer (<= 1000px and <= 256KB),
//     avoids redundant lossy re-encoding.
//  2. Otherwise decodes JPEG/PNG/GIF image from raw bytes (stripping EXIF metadata),
//     scales down dimensions exceeding MaxAttachmentDimension (1000px), and iteratively compresses
//     as JPEG from quality 90 down to 20 in steps of 10 until size <= MaxCompressedImageBytes.
func compressImageForTor(data []byte, mimeType string) ([]byte, string, int, int, error) {
	if !strings.HasPrefix(mimeType, "image/") || mimeType == "image/gif" {
		w, h := extractImageDimensions(data)
		return data, mimeType, w, h, nil
	}

	// Fast check: if already a compact JPEG within Briar's dimension and byte budget, skip double encoding.
	if mimeType == "image/jpeg" && len(data) <= MaxCompressedImageBytes {
		w, h := extractImageDimensions(data)
		if w > 0 && h > 0 && w <= MaxAttachmentDimension && h <= MaxAttachmentDimension {
			return data, mimeType, w, h, nil
		}
	}

	srcImg, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		// If decoding fails (e.g., unsupported subformat), fall back to raw bytes safely
		return data, mimeType, 0, 0, nil
	}

	bounds := srcImg.Bounds()
	srcW := bounds.Dx()
	srcH := bounds.Dy()
	if srcW <= 0 || srcH <= 0 {
		return data, mimeType, 0, 0, nil
	}

	// Calculate target dimensions preserving aspect ratio (max 1000px as in Briar)
	dstW, dstH := srcW, srcH
	if srcW > MaxAttachmentDimension || srcH > MaxAttachmentDimension {
		if srcW >= srcH {
			dstW = MaxAttachmentDimension
			dstH = (srcH * MaxAttachmentDimension) / srcW
			if dstH < 1 {
				dstH = 1
			}
		} else {
			dstH = MaxAttachmentDimension
			dstW = (srcW * MaxAttachmentDimension) / srcH
			if dstW < 1 {
				dstW = 1
			}
		}
	}

	// Resample into a clean RGBA buffer (strips all EXIF/metadata chunks)
	var targetImg image.Image = srcImg
	if dstW != srcW || dstH != srcH {
		scaled := image.NewRGBA(image.Rect(0, 0, dstW, dstH))
		for y := 0; y < dstH; y++ {
			srcY := bounds.Min.Y + (y*srcH)/dstH
			for x := 0; x < dstW; x++ {
				srcX := bounds.Min.X + (x*srcW)/dstW
				scaled.Set(x, y, srcImg.At(srcX, srcY))
			}
		}
		targetImg = scaled
	}

	// Iterative JPEG compression loop (Briar ImageCompressorImpl: quality 90 -> 20 step 10)
	var bestBuf bytes.Buffer
	for quality := 90; quality >= 20; quality -= 10 {
		bestBuf.Reset()
		err := jpeg.Encode(&bestBuf, targetImg, &jpeg.Options{Quality: quality})
		if err != nil {
			return data, mimeType, srcW, srcH, err
		}
		if bestBuf.Len() <= MaxCompressedImageBytes {
			break
		}
	}

	return bestBuf.Bytes(), "image/jpeg", dstW, dstH, nil
}

// stageLocalAttachment mirrors Briar's AttachmentCreatorImpl + MessagingManager.addLocalAttachment:
//  1. Sanitizes filename and detects MIME type.
//  2. If the attachment is an image, applies Briar-style dimension scaling, EXIF removal, and JPEG compression.
//  3. Computes the content-addressed SHA-256 Checksum and TransferID.
//  4. If saveLocalCopy is true, saves a local copy in "<downloadDir>/sent/" (unique name, never overwrites)
//     so the sender UI can render the attachment immediately.
func stageLocalAttachment(filename string, mimeType string, caption string, rawData []byte, saveLocalCopy bool) (*AttachmentHeader, []byte, error) {
	if len(rawData) == 0 {
		return nil, nil, fmt.Errorf("empty attachment data")
	}
	if int64(len(rawData)) > MaxFileTransferBytes {
		return nil, nil, fmt.Errorf("file exceeds maximum transfer size (%s)", FormatFileSize(MaxFileTransferBytes))
	}

	cleanName := sanitizeFilename(filename)
	if mimeType == "" {
		mimeType = DetectMimeType(cleanName, rawData)
	}

	processedData := rawData
	var width, height int
	if strings.HasPrefix(mimeType, "image/") {
		compressed, newMime, w, h, err := compressImageForTor(rawData, mimeType)
		if err == nil && len(compressed) > 0 {
			processedData = compressed
			mimeType = newMime
			width = w
			height = h
			if newMime == "image/jpeg" && !strings.HasSuffix(strings.ToLower(cleanName), ".jpg") && !strings.HasSuffix(strings.ToLower(cleanName), ".jpeg") {
				ext := filepath.Ext(cleanName)
				cleanName = strings.TrimSuffix(cleanName, ext) + ".jpg"
			}
		}
	}

	hasher := sha256.New()
	hasher.Write(processedData)
	checksum := hex.EncodeToString(hasher.Sum(nil))
	transferID := newTransferID(checksum)

	fileSize := int64(len(processedData))
	if fileSize > MaxFileTransferBytes {
		return nil, nil, fmt.Errorf("processed file exceeds maximum transfer size (%s)", FormatFileSize(MaxFileTransferBytes))
	}
	totalChunks := expectedChunkCount(fileSize)

	// Store local copy for sender preview (like Briar's local Attachment database storage)
	var localPath string
	if saveLocalCopy {
		sentDir := filepath.Join(GetDownloadDirectory(), "sent")
		if err := os.MkdirAll(sentDir, 0700); err == nil {
			candidate := uniqueFilePath(sentDir, cleanName)
			if err := os.WriteFile(candidate, processedData, 0600); err == nil {
				localPath = candidate
			}
		}
	}

	header := &AttachmentHeader{
		TransferID:  transferID,
		Filename:    cleanName,
		MimeType:    mimeType,
		FileSize:    fileSize,
		TotalChunks: totalChunks,
		Checksum:    checksum,
		Width:       width,
		Height:      height,
		Caption:     caption,
		LocalPath:   localPath,
		State:       AttachmentStateAvailable,
	}

	return header, processedData, nil
}

// SendAttachmentWithHeader broadcasts a staged AttachmentHeader and its binary data in 16KB chunks.
func SendAttachmentWithHeader(header *AttachmentHeader, data []byte, outgoing chan<- string) error {
	if header == nil || len(data) == 0 {
		return fmt.Errorf("invalid attachment header or empty data")
	}

	offer := FileMessage{
		Type:        "file_offer",
		TransferID:  header.TransferID,
		Filename:    header.Filename,
		MimeType:    header.MimeType,
		FileSize:    header.FileSize,
		TotalChunks: header.TotalChunks,
		Checksum:    header.Checksum,
		Width:       header.Width,
		Height:      header.Height,
		Caption:     header.Caption,
	}
	offerJSON, err := json.Marshal(offer)
	if err != nil {
		return fmt.Errorf("failed to marshal file_offer: %w", err)
	}
	outgoing <- string(offerJSON)

	for i := 0; i < header.TotalChunks; i++ {
		start := i * FileChunkSize
		end := start + FileChunkSize
		if end > len(data) {
			end = len(data)
		}

		encoded := base64.StdEncoding.EncodeToString(data[start:end])

		chunk := FileMessage{
			Type:        "file_chunk",
			TransferID:  header.TransferID,
			Filename:    header.Filename,
			MimeType:    header.MimeType,
			ChunkNum:    i,
			TotalChunks: header.TotalChunks,
			Data:        encoded,
		}
		chunkJSON, err := json.Marshal(chunk)
		if err != nil {
			return fmt.Errorf("failed to marshal file_chunk %d: %w", i, err)
		}
		outgoing <- string(chunkJSON)
	}

	endMsg := FileMessage{
		Type:        "file_end",
		TransferID:  header.TransferID,
		Filename:    header.Filename,
		MimeType:    header.MimeType,
		TotalChunks: header.TotalChunks,
		Checksum:    header.Checksum,
		Width:       header.Width,
		Height:      header.Height,
		Caption:     header.Caption,
	}
	endJSON, err := json.Marshal(endMsg)
	if err != nil {
		return fmt.Errorf("failed to marshal file_end: %w", err)
	}
	outgoing <- string(endJSON)

	return nil
}

// SendFile reads a file from disk and broadcasts it in chunks through the outgoing channel.
func SendFile(filePath string, outgoing chan<- string) error {
	file, err := os.Open(filePath)
	if err != nil {
		return fmt.Errorf("failed to open file: %w", err)
	}
	defer file.Close()

	stat, err := file.Stat()
	if err != nil {
		return fmt.Errorf("failed to stat file: %w", err)
	}

	filename := sanitizeFilename(stat.Name())
	fileSize := stat.Size()
	if fileSize == 0 {
		return fmt.Errorf("cannot send empty file: %s", filename)
	}
	if fileSize > MaxFileTransferBytes {
		return fmt.Errorf("file exceeds maximum transfer size (%s): %s", FormatFileSize(MaxFileTransferBytes), filename)
	}

	// Calculate the SHA-256 checksum and sniff initial bytes for MIME detection.
	hasher := sha256.New()
	headBuf := make([]byte, 512)
	nHead, _ := file.Read(headBuf)
	if nHead > 0 {
		hasher.Write(headBuf[:nHead])
	}
	if _, err := io.Copy(hasher, file); err != nil {
		return fmt.Errorf("failed to calculate checksum: %w", err)
	}
	checksum := hex.EncodeToString(hasher.Sum(nil))
	transferID := newTransferID(checksum)
	mimeType := DetectMimeType(filename, headBuf[:nHead])

	// Reset the file pointer to the beginning after checksum calculation.
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("failed to reset file: %w", err)
	}

	totalChunks := int((fileSize + FileChunkSize - 1) / FileChunkSize)

	// Broadcast the file offer message.
	offer := FileMessage{
		Type:        "file_offer",
		TransferID:  transferID,
		Filename:    filename,
		MimeType:    mimeType,
		FileSize:    fileSize,
		TotalChunks: totalChunks,
		Checksum:    checksum,
	}
	offerJSON, _ := json.Marshal(offer)
	outgoing <- string(offerJSON)

	// Stream file chunks.
	buffer := make([]byte, FileChunkSize)
	chunkNum := 0
	for {
		n, err := file.Read(buffer)
		if err == io.EOF {
			break
		}
		if err != nil {
			errMsg := FileMessage{
				Type:       "file_error",
				TransferID: transferID,
				Filename:   filename,
				Data:       err.Error(),
			}
			errJSON, _ := json.Marshal(errMsg)
			outgoing <- string(errJSON)
			return fmt.Errorf("failed to read chunk: %w", err)
		}

		encoded := base64.StdEncoding.EncodeToString(buffer[:n])

		chunk := FileMessage{
			Type:        "file_chunk",
			TransferID:  transferID,
			Filename:    filename,
			MimeType:    mimeType,
			ChunkNum:    chunkNum,
			TotalChunks: totalChunks,
			Data:        encoded,
		}
		chunkJSON, _ := json.Marshal(chunk)
		outgoing <- string(chunkJSON)

		chunkNum++
	}

	// Broadcast the completion message.
	end := FileMessage{
		Type:        "file_end",
		TransferID:  transferID,
		Filename:    filename,
		MimeType:    mimeType,
		TotalChunks: totalChunks,
		Checksum:    checksum,
	}
	endJSON, _ := json.Marshal(end)
	outgoing <- string(endJSON)

	return nil
}

// SendFileFromBytes broadcasts data provided as a byte slice as a file through the outgoing channel.
// It does not keep a local copy: callers of this legacy entrypoint (e.g. MainActivity.sendFile) manage
// their own sender-side copy. Use SendMediaAttachmentAndroid to have Go stage the local copy.
func SendFileFromBytes(filename string, data []byte, outgoing chan<- string) error {
	header, processedData, err := stageLocalAttachment(filename, "", "", data, false)
	if err != nil {
		return err
	}
	return SendAttachmentWithHeader(header, processedData, outgoing)
}

// SendFolder broadcasts every file within the 'loadingbay_out' directory.
func SendFolder(outgoing chan<- string) error {
	loadingBayOut := "loadingbay_out"

	if _, err := os.Stat(loadingBayOut); os.IsNotExist(err) {
		return fmt.Errorf("loadingbay_out folder not found")
	}

	entries, err := os.ReadDir(loadingBayOut)
	if err != nil {
		return fmt.Errorf("failed to read loadingbay_out: %w", err)
	}

	filesSent := 0
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}

		filePath := filepath.Join(loadingBayOut, entry.Name())
		if err := SendFile(filePath, outgoing); err != nil {
			return fmt.Errorf("failed to send %s: %w", entry.Name(), err)
		}
		filesSent++
	}

	if filesSent == 0 {
		return fmt.Errorf("no files found in loadingbay_out")
	}

	return nil
}

// ReceiveFileChunk processes incoming file transfer messages that are not associated with a specific peer.
// StartEventLoop uses receiveFileChunkFrom so transfers are scoped per peer.
func ReceiveFileChunk(msg FileMessage, transfers map[string]*FileTransferState) (*FileTransferState, error) {
	return receiveFileChunkFrom("", msg, transfers)
}

// receiveFileChunkFrom processes incoming file transfer messages in a concurrency-safe, idempotent manner
// and supports out-of-order chunk/end arrival over Tor.
//
// Return contract:
//   - (state, nil): message accepted. state.Completed is true once the file is verified and saved.
//     A file_end that arrives before the last chunk returns (state, nil) with Completed == false;
//     the transfer finalizes when the missing chunks arrive.
//   - (nil, err wrapping errTransferRejected): a file_offer was refused; nothing is buffered.
//   - (nil, err wrapping errTransferFailed): an active transfer was aborted and discarded.
//   - (nil, other err): message ignored (unknown transfer, abort notice, unknown type).
func receiveFileChunkFrom(peerID string, msg FileMessage, transfers map[string]*FileTransferState) (*FileTransferState, error) {
	transfersMu.Lock()
	defer transfersMu.Unlock()

	cleanName := sanitizeFilename(msg.Filename)
	key := scopedTransferKey(peerID, msg)

	switch msg.Type {
	case "file_offer":
		purgeStaleTransfersLocked(transfers, time.Now())

		// Empty files are valid: Rizoma Desktop sends them as file_size 0, total_chunks 0, no
		// chunks and a file_end, and saves them on its side.
		if msg.FileSize < 0 || msg.FileSize > MaxFileTransferBytes {
			return nil, fmt.Errorf("%w: invalid file size %d (max %s)", errTransferRejected, msg.FileSize, FormatFileSize(MaxFileTransferBytes))
		}
		if want := expectedChunkCount(msg.FileSize); msg.TotalChunks != want {
			return nil, fmt.Errorf("%w: declared %d chunks but %d bytes require %d", errTransferRejected, msg.TotalChunks, msg.FileSize, want)
		}
		if !isSHA256Hex(msg.Checksum) {
			return nil, fmt.Errorf("%w: missing or invalid SHA-256 checksum", errTransferRejected)
		}
		if _, exists := transfers[key]; !exists && len(transfers) >= MaxConcurrentTransfers {
			return nil, fmt.Errorf("%w: too many concurrent transfers (max %d)", errTransferRejected, MaxConcurrentTransfers)
		}

		mimeType := msg.MimeType
		if mimeType == "" {
			mimeType = DetectMimeType(cleanName, nil)
		}
		state := &FileTransferState{
			TransferID:  transferKey(msg),
			PeerID:      peerID,
			StartedAt:   time.Now(),
			Filename:    cleanName,
			MimeType:    mimeType,
			FileSize:    msg.FileSize,
			TotalChunks: msg.TotalChunks,
			Checksum:    strings.ToLower(msg.Checksum),
			Width:       msg.Width,
			Height:      msg.Height,
			Caption:     msg.Caption,
			Chunks:      make(map[int][]byte),
			Progress:    0,
			State:       AttachmentStateLoading,
		}
		transfers[key] = state
		return state, nil

	case "file_chunk":
		state, exists := transfers[key]
		if !exists {
			return nil, fmt.Errorf("received chunk for unknown file: %s", cleanName)
		}

		if msg.ChunkNum < 0 || msg.ChunkNum >= state.TotalChunks {
			return abortTransferLocked(state, key, transfers, fmt.Errorf("chunk index %d out of bounds (total %d)", msg.ChunkNum, state.TotalChunks))
		}

		data, err := base64.StdEncoding.DecodeString(msg.Data)
		if err != nil {
			return abortTransferLocked(state, key, transfers, fmt.Errorf("failed to decode chunk %d: %v", msg.ChunkNum, err))
		}
		if len(data) == 0 || len(data) > FileChunkSize {
			return abortTransferLocked(state, key, transfers, fmt.Errorf("chunk %d has invalid size %d", msg.ChunkNum, len(data)))
		}

		// Idempotent chunk recording: only count this chunk if its index was not yet stored
		if _, alreadyPresent := state.Chunks[msg.ChunkNum]; !alreadyPresent {
			if state.BytesReceived+int64(len(data)) > state.FileSize {
				return abortTransferLocked(state, key, transfers, fmt.Errorf("received data exceeds declared size %d", state.FileSize))
			}
			state.Chunks[msg.ChunkNum] = data
			state.BytesReceived += int64(len(data))
			state.Progress = len(state.Chunks)
		}

		// Sniff MIME type from chunk 0 if not provided in offer
		if msg.ChunkNum == 0 && (state.MimeType == "" || state.MimeType == "application/octet-stream") {
			state.MimeType = DetectMimeType(state.Filename, data)
		}

		// If file_end had already arrived out-of-order and all chunks are now present, finalize automatically
		if state.EndReceived && state.Progress == state.TotalChunks {
			return finalizeTransferLocked(state, key, transfers)
		}

		return state, nil

	case "file_end", "file_complete":
		state, exists := transfers[key]
		if !exists {
			return nil, fmt.Errorf("received file_end for unknown file: %s", cleanName)
		}

		// The checksum is fixed by the offer; a different one at the end means the stream is inconsistent.
		if msg.Checksum != "" && !strings.EqualFold(msg.Checksum, state.Checksum) {
			return abortTransferLocked(state, key, transfers, fmt.Errorf("checksum in file_end does not match the offer"))
		}

		state.EndReceived = true
		if state.Progress < state.TotalChunks {
			// Out-of-order arrival: wait for the remaining chunks instead of reporting an error.
			return state, nil
		}

		return finalizeTransferLocked(state, key, transfers)

	case "file_error", "file_cancel":
		if state, exists := transfers[key]; exists {
			state.State = AttachmentStateError
		}
		delete(transfers, key)
		return nil, fmt.Errorf("file transfer aborted: %s", msg.Data)

	default:
		return nil, fmt.Errorf("unknown file message type: %s", msg.Type)
	}
}

// abortTransferLocked discards an active transfer and returns an error wrapping errTransferFailed.
// Caller must hold transfersMu.
func abortTransferLocked(state *FileTransferState, key string, transfers map[string]*FileTransferState, cause error) (*FileTransferState, error) {
	state.State = AttachmentStateError
	delete(transfers, key)
	return nil, fmt.Errorf("%w: %v", errTransferFailed, cause)
}

// finalizeTransferLocked writes, verifies and saves a complete transfer. Caller must hold transfersMu.
func finalizeTransferLocked(state *FileTransferState, key string, transfers map[string]*FileTransferState) (*FileTransferState, error) {
	if state.BytesReceived != state.FileSize {
		return abortTransferLocked(state, key, transfers, fmt.Errorf("received %d bytes but offer declared %d", state.BytesReceived, state.FileSize))
	}
	savedPath, err := saveFile(state, state.Checksum)
	if err != nil {
		return abortTransferLocked(state, key, transfers, err)
	}
	delete(transfers, key)
	state.Completed = true
	state.SavedPath = savedPath
	state.State = AttachmentStateAvailable
	return state, nil
}

// uniqueFilePath ensures existing downloaded files with the same name are not silently overwritten.
func uniqueFilePath(dir, cleanName string) string {
	candidate := filepath.Join(dir, cleanName)
	if _, err := os.Stat(candidate); os.IsNotExist(err) {
		return candidate
	}
	ext := filepath.Ext(cleanName)
	base := strings.TrimSuffix(cleanName, ext)
	for i := 1; i < 10000; i++ {
		alt := filepath.Join(dir, fmt.Sprintf("%s_%d%s", base, i, ext))
		if _, err := os.Stat(alt); os.IsNotExist(err) {
			return alt
		}
	}
	return filepath.Join(dir, fmt.Sprintf("%s_%d%s", base, time.Now().UnixNano(), ext))
}

// saveFile reassembles chunks into a temporary .part file, verifies integrity via SHA-256 checksum,
// and atomically renames the verified file into the configured download directory.
func saveFile(state *FileTransferState, checksum string) (string, error) {
	if !isSHA256Hex(checksum) {
		return "", fmt.Errorf("refusing to save file without a valid SHA-256 checksum")
	}

	targetDir := GetDownloadDirectory()

	if err := os.MkdirAll(targetDir, 0700); err != nil {
		return "", fmt.Errorf("failed to create download directory: %w", err)
	}

	cleanName := sanitizeFilename(state.Filename)
	finalPath := uniqueFilePath(targetDir, cleanName)
	partPath := finalPath + ".part"

	outFile, err := os.OpenFile(partPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0600)
	if err != nil {
		return "", fmt.Errorf("failed to create temporary output file: %w", err)
	}

	hasher := sha256.New()
	for i := 0; i < state.TotalChunks; i++ {
		chunk, exists := state.Chunks[i]
		if !exists {
			outFile.Close()
			_ = os.Remove(partPath)
			return "", fmt.Errorf("missing chunk %d", i)
		}

		if _, err := outFile.Write(chunk); err != nil {
			outFile.Close()
			_ = os.Remove(partPath)
			return "", fmt.Errorf("failed to write chunk %d: %w", i, err)
		}
		hasher.Write(chunk)
	}

	if err := outFile.Close(); err != nil {
		_ = os.Remove(partPath)
		return "", fmt.Errorf("failed to close temporary file: %w", err)
	}

	actualChecksum := hex.EncodeToString(hasher.Sum(nil))
	if !strings.EqualFold(actualChecksum, checksum) {
		_ = os.Remove(partPath)
		return "", fmt.Errorf("checksum mismatch: expected %s, got %s", checksum, actualChecksum)
	}

	if err := os.Rename(partPath, finalPath); err != nil {
		_ = os.Remove(partPath)
		return "", fmt.Errorf("failed to finalize file path: %w", err)
	}

	return finalPath, nil
}

// SaveFileFromBytes saves raw binary data directly to the configured download directory under the sanitized filename.
func SaveFileFromBytes(filename string, data []byte) error {
	targetDir := GetDownloadDirectory()
	if err := os.MkdirAll(targetDir, 0755); err != nil {
		return fmt.Errorf("failed to create download dir: %w", err)
	}
	outPath := uniqueFilePath(targetDir, sanitizeFilename(filename))
	return os.WriteFile(outPath, data, 0644)
}

// FormatFileSize converts a byte count into a human-readable string (e.g., KB, MB, GB).
func FormatFileSize(bytesCount int64) string {
	const unit = 1024
	if bytesCount < unit {
		return fmt.Sprintf("%d B", bytesCount)
	}
	div, exp := int64(unit), 0
	for n := bytesCount / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(bytesCount)/float64(div), "KMGTPE"[exp])
}
