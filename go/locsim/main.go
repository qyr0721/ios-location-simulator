package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/danielpaulus/go-ios/ios"
	"github.com/danielpaulus/go-ios/ios/instruments"
	"github.com/danielpaulus/go-ios/ios/tunnel"
)

// locsim keeps one DVT LocationSimulation session open and updates the
// simulated coordinate on every "S lat lon" line from stdin. The Android app
// writes those lines while playing a route, so the iPhone sees a continuous
// movement instead of a new process/tunnel handshake per point.
//
// Commands:
//   S <lat> <lon>   set location
//   X               stop simulation (closes the DVT session)
//   Q               quit
//
// Environment: USBMUXD_SOCKET_ADDRESS must point at the app's local usbmuxd
// (tcp://127.0.0.1:27015). The go-ios tunnel agent is expected on
// 127.0.0.1:28100 (started by the app).

func out(v map[string]interface{}) {
	b, _ := json.Marshal(v)
	fmt.Println(string(b))
}

func fail(err error) {
	out(map[string]interface{}{"status": "error", "error": err.Error()})
	os.Exit(1)
}

func main() {
	udid := ""
	if len(os.Args) > 1 {
		udid = os.Args[1]
	}
	host := "127.0.0.1"
	port := 28100

	device, err := ios.GetDevice(udid)
	if err != nil {
		fail(err)
	}
	info, err := tunnel.TunnelInfoForDevice(device.Properties.SerialNumber, host, port)
	if err != nil {
		fail(err)
	}

	device.UserspaceTUN = info.UserspaceTUN
	device.UserspaceTUNHost = host
	device.UserspaceTUNPort = info.UserspaceTUNPort

	rsd, err := ios.NewWithAddrPortDevice(info.Address, info.RsdPort, device)
	if err != nil {
		fail(err)
	}
	provider, err := rsd.Handshake()
	if err != nil {
		_ = rsd.Close()
		fail(err)
	}
	_ = rsd.Close()

	dev, err := ios.GetDeviceWithAddress(device.Properties.SerialNumber, info.Address, provider)
	if err != nil {
		fail(err)
	}
	dev.UserspaceTUN = info.UserspaceTUN
	dev.UserspaceTUNHost = host
	dev.UserspaceTUNPort = info.UserspaceTUNPort

	svc, err := instruments.NewLocationSimulationService(dev)
	if err != nil {
		fail(err)
	}
	out(map[string]interface{}{"status": "ready", "udid": dev.Properties.SerialNumber})

	scanner := bufio.NewScanner(os.Stdin)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		switch {
		case line == "Q" || line == "q":
			_ = svc.StopSimulateLocation()
			svc.Close()
			return
		case line == "X" || line == "x" || line == "stop":
			if err := svc.StopSimulateLocation(); err != nil {
				out(map[string]interface{}{"status": "error", "error": err.Error()})
			} else {
				out(map[string]interface{}{"status": "stopped"})
			}
			// StopSimulateLocation closes the DTX connection; exit so the app
			// starts a fresh session for the next request.
			svc.Close()
			return
		default:
			parts := strings.Fields(line)
			if parts[0] == "S" || parts[0] == "s" {
				parts = parts[1:]
			}
			if len(parts) < 2 {
				out(map[string]interface{}{"status": "error", "error": "expected: S lat lon"})
				continue
			}
			lat, e1 := strconv.ParseFloat(parts[0], 64)
			lon, e2 := strconv.ParseFloat(parts[1], 64)
			if e1 != nil || e2 != nil {
				out(map[string]interface{}{"status": "error", "error": "invalid coordinates"})
				continue
			}
			if err := svc.StartSimulateLocation(lat, lon); err != nil {
				out(map[string]interface{}{"status": "error", "error": err.Error()})
			} else {
				out(map[string]interface{}{"status": "ok", "lat": lat, "lon": lon})
			}
		}
	}
	_ = svc.StopSimulateLocation()
	svc.Close()
}
