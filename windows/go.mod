module github.com/dzshzx/Zeta/windows

go 1.27.4

require (
	github.com/dzshzx/Zeta/client v0.0.0
	github.com/dzshzx/Zeta/helper v0.0.0
	github.com/dzshzx/Zeta/hub v0.0.0
	github.com/egoist/mygo v0.10.0
)

require (
	github.com/ProtonMail/go-crypto v1.4.1 // indirect
	github.com/cloudflare/circl v1.6.3 // indirect
	github.com/ebitengine/purego v0.11.3 // indirect
	github.com/go-text/typesetting v0.3.5 // indirect
	github.com/klauspost/compress v1.18.7 // indirect
	github.com/tetratelabs/wazero v1.12.0 // indirect
	github.com/ulikunitz/xz v0.5.15 // indirect
	go.digitalxero.dev/rpm v0.3.4 // indirect
	golang.org/x/crypto v0.59.0 // indirect
	golang.org/x/image v0.48.0 // indirect
	golang.org/x/net v0.63.0 // indirect
	golang.org/x/sys v0.50.0 // indirect
	golang.org/x/text v0.44.0 // indirect
)

tool github.com/egoist/mygo/cmd/mygo

replace github.com/dzshzx/Zeta/helper => ../helper

replace github.com/dzshzx/Zeta/client => ../client

replace github.com/dzshzx/Zeta/hub => ../hub
