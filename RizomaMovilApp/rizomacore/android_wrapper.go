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
}

// ChatPayload represents the standard JSON chat message exchanged between Rizoma peers.
type ChatPayload struct {
	Content string `json:"content"`
	Name    string `json:"name"`
	Color   string `json:"color"`
}

// ConnectAndroid wraps the Connect method for gomobile with default secret.
func (nm *NetworkManager) ConnectAndroid(address string) error {
	return nm.ConnectWithSecretAndroid(address, VoidSecret)
}

// ConnectWithSecretAndroid connects to an onion address with a shared secret.
func (nm *NetworkManager) ConnectWithSecretAndroid(address, secret string) error {
	addr := strings.TrimSpace(address)
	addr = strings.TrimPrefix(addr, "http://")
	addr = strings.TrimPrefix(addr, "https://")
	addr = strings.TrimSuffix(addr, "/")
	addr = strings.TrimSuffix(addr, ":80")

	if secret == "" {
		secret = VoidSecret
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

	err := nm.Host(context.Background(), "Host", VoidSecret, nil, progress)
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
	go func() {
		for msg := range nm.Incoming {
			if strings.HasPrefix(msg, "PEER_MSG:") {
				parts := strings.SplitN(msg, ":", 5)
				if len(parts) == 5 {
					peerName := parts[2]
					peerColor := parts[3]
					content := parts[4]

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

					cb.OnPeerMessage(peerName, peerColor, content)
					continue
				}
			}
			cb.OnSystemMessage(msg)
		}
	}()

	go func() {
		for err := range nm.ErrorChan {
			cb.OnSystemMessage("ERROR: " + err.Error())
		}
	}()
}
