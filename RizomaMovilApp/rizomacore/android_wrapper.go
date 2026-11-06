package rizomacore

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// AndroidCallback allows the Android Kotlin code to receive callbacks from Go.
// Preserves the exact 5-method signature expected by MainActivity.kt and RizomaCore.aar.
type AndroidCallback interface {
	OnSystemMessage(msg string)
	OnPeerMessage(senderName string, senderColor string, msg string)
	OnFileOffer(filename string, fileSize int64, fromPeer string)
	OnFileProgress(filename string, progress int64, total int64)
	OnFileReceived(filename string, localPath string, fromPeer string)
}

// ChatPayload represents the standard JSON chat message exchanged between Rizoma peers.
// Extended with optional Briar-style AttachmentHeader metadata for multimedia messages.
type ChatPayload struct {
	Content    string            `json:"content"`
	Name       string            `json:"name"`
	Color      string            `json:"color"`
	Whisper    bool              `json:"whisper,omitempty"` // Desktop's private /whisper messages (sent with SendToPeer)
	Attachment *AttachmentHeader `json:"attachment,omitempty"`
}

// ConnectAndroid wraps the Connect method for gomobile without an upfront secret (discovers secret dynamically).
func (nm *NetworkManager) ConnectAndroid(address string) error {
	return nm.ConnectWithSecretAndroid(address, "")
}

// ConnectWithSecretAndroid connects to an onion address with an optional shared secret.
func (nm *NetworkManager) ConnectWithSecretAndroid(address, secret string) error {
	addr := strings.TrimSpace(address)
	addr = strings.TrimPrefix(addr, "http://")
	addr = strings.TrimPrefix(addr, "https://")
	addr = strings.TrimSuffix(addr, "/")
	addr = strings.TrimSuffix(addr, ":80")
	// Desktop always dials "<id>.onion": its UI appends ".onion" when missing (e.g. after
	// decrypting a shared session key, which only contains the service ID). Tor's SOCKS proxy
	// treats a bare ID as a normal hostname, so do the same here.
	if addr != "" && !strings.HasSuffix(addr, ".onion") {
		addr += ".onion"
	}

	progress := make(chan string, 20)
	go func() {
		for p := range progress {
			nm.Incoming <- p
		}
	}()

	return nm.Connect(context.Background(), addr, secret, progress)
}

// HostAndroid wraps the Host method for gomobile without context.
func (nm *NetworkManager) HostAndroid() (string, error) {
	progress := make(chan string, 20)
	go func() {
		for p := range progress {
			nm.Incoming <- p
		}
	}()

	err := nm.Host(context.Background(), "Host", "", nil, progress)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("Listening on %s.onion", nm.OnionAddr), nil
}

// JoinOrHostVoidAndroid connects to or hosts the shared Void instance.
func (nm *NetworkManager) JoinOrHostVoidAndroid() error {
	progress := make(chan string, 20)
	go func() {
		for p := range progress {
			nm.Incoming <- p
		}
	}()
	return nm.JoinOrHostVoid(context.Background(), progress)
}

// BroadcastAndroid sends a text message to all peers packaged in the standard JSON structure.
func (nm *NetworkManager) BroadcastAndroid(msg string) {
	nm.BroadcastWithIdentityAndroid(msg, "MobileUser", "205")
}

// BroadcastWithIdentityAndroid sends a message with custom sender nickname and color.
func (nm *NetworkManager) BroadcastWithIdentityAndroid(msg, name, color string) {
	if name == "" {
		name = "MobileUser"
	}
	if color == "" {
		color = "205"
	}

	payload, err := json.Marshal(ChatPayload{
		Content: msg,
		Name:    name,
		Color:   color,
	})
	if err == nil {
		nm.Outgoing <- string(payload)
	} else {
		nm.Outgoing <- msg
	}
}

// StartEventLoop consumes channels and triggers the Android callback.
// Supports Briar-inspired progressive attachment states, out-of-order chunk completion,
// and optional captions attached to multimedia files.
func (nm *NetworkManager) StartEventLoop(cb AndroidCallback) {
	transfers := make(map[string]*FileTransferState)

	go func() {
		for msg := range nm.Incoming {
			if strings.HasPrefix(msg, "PEER_MSG:") {
				parts := strings.SplitN(msg, ":", 5)
				if len(parts) == 5 {
					peerID := parts[1]
					peerName := parts[2]
					peerColor := parts[3]
					content := parts[4]

					// 1. Try parsing as FileMessage
					var fileMsg FileMessage
					if json.Unmarshal([]byte(content), &fileMsg) == nil && fileMsg.Type != "" {
						// Desktop's host relays every file message to the other peers, except
						// bradbury_offer (it carries no data: each receiver uses its own embedded copy).
						if fileMsg.Type != "bradbury_offer" {
							nm.relayFromPeer(peerID, content)
						}

						state, err := receiveFileChunkFrom(peerID, fileMsg, transfers)
						if cb == nil {
							continue
						}

						// Rejected offers and aborted transfers are always reported; other errors
						// (e.g. chunks for an already discarded transfer) are ignored silently.
						if err != nil {
							displayName := sanitizeFilename(fileMsg.Filename)
							switch {
							case errors.Is(err, errTransferRejected):
								cb.OnSystemMessage(fmt.Sprintf("File offer from %s rejected (%s): %v", peerName, displayName, err))
							case errors.Is(err, errTransferFailed):
								cb.OnSystemMessage(fmt.Sprintf("File transfer from %s failed (%s): %v", peerName, displayName, err))
							case fileMsg.Type == "file_error" || fileMsg.Type == "file_cancel":
								cb.OnSystemMessage(fmt.Sprintf("File transfer error from %s: %s", peerName, fileMsg.Data))
							}
							continue
						}
						if state == nil {
							continue
						}

						switch fileMsg.Type {
						case "file_offer":
							cb.OnFileOffer(state.Filename, state.FileSize, peerName)
						case "file_chunk":
							cb.OnFileProgress(state.Filename, int64(state.Progress), int64(state.TotalChunks))
						}

						// Completion can be triggered by file_end or, if it arrived early, by the last chunk.
						if state.Completed && state.SavedPath != "" {
							cb.OnFileReceived(state.Filename, state.SavedPath, peerName)
							if state.Caption != "" {
								cb.OnPeerMessage(peerName, peerColor, state.Caption)
							}
						}
						continue
					}

					// 2. Parse regular chat message
					var payload ChatPayload
					if json.Unmarshal([]byte(content), &payload) == nil {
						// Desktop's host relays every chat message to the other peers, except whispers.
						if !payload.Whisper {
							nm.relayFromPeer(peerID, content)
						}
						if payload.Content != "" {
							if payload.Name != "" {
								peerName = payload.Name
							}
							if payload.Color != "" {
								peerColor = payload.Color
							}
							content = payload.Content
							if payload.Whisper {
								// Desktop shows these as "[whisper from <name>]"; keep them distinguishable
								// from public messages, since AndroidCallback has no private-message flag.
								content = "[whisper] " + content
							}
						}
					}

					if cb != nil {
						cb.OnPeerMessage(peerName, peerColor, content)
					}
					continue
				}
			}
			if cb != nil {
				cb.OnSystemMessage(msg)
			}
		}
	}()

	go func() {
		for err := range nm.ErrorChan {
			if cb != nil {
				cb.OnSystemMessage("ERROR: " + err.Error())
			}
		}
	}()
}

// SendFileBytesAndroid broadcasts file data as an encrypted chunk stream (backward-compatible entrypoint).
func (nm *NetworkManager) SendFileBytesAndroid(filename string, data []byte) error {
	if len(data) == 0 {
		return fmt.Errorf("empty file data")
	}
	return SendFileFromBytes(filename, data, nm.Outgoing)
}

// SendMediaAttachmentAndroid implements Briar's AttachmentCreator + PrivateMessage workflow for Android:
//  1. Stages and compresses (if image) the attachment locally in "<storageDir>/sent/".
//  2. Streams the attachment offer, 16KB chunks, and completion frame over Tor.
//  3. Returns the serialized AttachmentHeader JSON (including local_path, mime_type, width, height, checksum)
//     so the Android UI can immediately display the multimedia bubble in the conversation.
func (nm *NetworkManager) SendMediaAttachmentAndroid(filename string, mimeType string, caption string, data []byte) (string, error) {
	header, processedData, err := stageLocalAttachment(filename, mimeType, caption, data, true)
	if err != nil {
		return "", err
	}

	if err := SendAttachmentWithHeader(header, processedData, nm.Outgoing); err != nil {
		return "", err
	}

	headerJSON, err := json.Marshal(header)
	if err != nil {
		return "", err
	}
	return string(headerJSON), nil
}

// SendFilePathAndroid broadcasts a file from the Android filesystem as an encrypted chunk stream.
func (nm *NetworkManager) SendFilePathAndroid(path string) error {
	return SendFile(path, nm.Outgoing)
}

// SetStorageDirectory configures where received files are saved on Android.
func (nm *NetworkManager) SetStorageDirectory(dir string) {
	SetDownloadDirectory(dir)
}
