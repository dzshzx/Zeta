module github.com/dzshzx/Zeta/hub

go 1.27.4

require (
	github.com/dzshzx/Zeta/client v0.0.0
	github.com/dzshzx/Zeta/helper v0.0.0
	github.com/egoist/mygo v0.10.0
	github.com/tetratelabs/wazero v1.12.0
	golang.org/x/crypto v0.59.0
	golang.org/x/image v0.48.0
)

require (
	golang.org/x/sys v0.50.0 // indirect
	golang.org/x/text v0.44.0 // indirect
)

replace github.com/dzshzx/Zeta/helper => ../helper

replace github.com/dzshzx/Zeta/client => ../client
