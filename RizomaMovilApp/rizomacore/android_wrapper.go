package rizomacore

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
)

// AndroidCallback allows the Android Kotlin code to receive callbacks from Go.
type AndroidCallback interface {
	OnSystemMessage(msg string)
	OnPeerMessage(senderName string, senderColor string, msg string)
	OnFileOffer(filename string, fileSize int64, fromPeer string)
	OnFileProgress(filename string, progress int64, total int64)
	OnFileReceived(filename string, localPath string, fromPeer string)
}

// ChatPayload represents the standard JSON chat message exchanged between Rizoma peers.
type ChatPayload struct {
	Content string `json:"content"`
	Name    string `json:"name"`
	Color   string `json:"color"`
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
	return fmt.Sprintf("Listening locally on port %d", nm.LocalPort), nil
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
func (nm *NetworkManager) StartEventLoop(cb AndroidCallback) {
	transfers := make(map[string]*FileTransferState)

	go func() {
		for msg := range nm.Incoming {
			if strings.HasPrefix(msg, "PEER_MSG:") {
				parts := strings.SplitN(msg, ":", 5)
				if len(parts) == 5 {
					peerName := parts[2]
					peerColor := parts[3]
					content := parts[4]

					// 1. Try parsing as FileMessage
					var fileMsg FileMessage
					if json.Unmarshal([]byte(content), &fileMsg) == nil && fileMsg.Type != "" {
						switch fileMsg.Type {
						case "file_offer":
							ReceiveFileChunk(fileMsg, transfers)
							if cb != nil {
								cb.OnFileOffer(fileMsg.Filename, fileMsg.FileSize, peerName)
							}
						case "file_chunk":
							state, err := ReceiveFileChunk(fileMsg, transfers)
							if err == nil && state != nil && cb != nil {
								cb.OnFileProgress(fileMsg.Filename, int64(state.Progress), int64(state.TotalChunks))
							}
						case "file_end":
							state, err := ReceiveFileChunk(fileMsg, transfers)
							if err == nil && state != nil && cb != nil {
								cb.OnFileReceived(fileMsg.Filename, state.SavedPath, peerName)
							}
						case "file_error":
							ReceiveFileChunk(fileMsg, transfers)
							if cb != nil {
								cb.OnSystemMessage(fmt.Sprintf("File transfer error from %s: %s", peerName, fileMsg.Data))
							}
						}
						continue
					}

					// 2. Parse regular chat message
					var payload ChatPayload
					if json.Unmarshal([]byte(content), &payload) == nil && payload.Content != "" {
						if payload.Name != "" {
							peerName = payload.Name
						}
						if payload.Color != "" {
							peerColor = payload.Color
						}
						content = payload.Content
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

// SendFileBytesAndroid broadcasts file data as an encrypted chunk stream.
func (nm *NetworkManager) SendFileBytesAndroid(filename string, data []byte) error {
	if len(data) == 0 {
		return fmt.Errorf("empty file data")
	}
	return SendFileFromBytes(filename, data, nm.Outgoing)
}

// SendFilePathAndroid broadcasts a file from the Android filesystem as an encrypted chunk stream.
func (nm *NetworkManager) SendFilePathAndroid(path string) error {
	return SendFile(path, nm.Outgoing)
}

// SetStorageDirectory configures where received files are saved on Android.
func (nm *NetworkManager) SetStorageDirectory(dir string) {
	SetDownloadDirectory(dir)
}
