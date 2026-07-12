package main

import (
	"fmt"
	"os"
)

type patch struct {
	name        string
	offset      int
	expected    []byte
	replacement []byte
}

func has(data []byte, offset int, want []byte) bool {
	if offset < 0 || offset+len(want) > len(data) {
		return false
	}
	for i := range want {
		if data[offset+i] != want[i] {
			return false
		}
	}
	return true
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: patchbytes <file>")
		os.Exit(2)
	}
	path := os.Args[1]
	data, err := os.ReadFile(path)
	if err != nil {
		panic(err)
	}
	backup := path + ".orig"
	if _, err := os.Stat(backup); os.IsNotExist(err) {
		if err := os.WriteFile(backup, data, 0o644); err != nil {
			panic(err)
		}
	}
	patches := []patch{

		{"libc openat2 immediate ENOSYS", 0x10faf0, []byte{0xf3, 0x0f, 0x1e, 0xfa}, []byte{0x6a, 0xda, 0x58, 0xc3}},
		{"libc startup rt_sigprocmask", 0x27765, []byte{0x0f, 0x05}, []byte{0x31, 0xc0}},
		{"libc pthread set_robust_list", 0x974cd, []byte{0x0f, 0x05}, []byte{0x31, 0xc0}},
		{"libc pthread rseq", 0x977b3, []byte{0x0f, 0x05}, []byte{0xf7, 0xd8}},
		{"libc fork set_robust_list", 0xe56dc, []byte{0x0f, 0x05}, []byte{0x31, 0xc0}},
		{"libc faccessat2 mov", 0x10a008, []byte{0xb8, 0xb7, 0x01, 0x00, 0x00}, []byte{0x6a, 0xda, 0x58, 0x90, 0x90}},
		{"libc faccessat2 syscall", 0x10a027, []byte{0x0f, 0x05}, []byte{0x90, 0x90}},
	}
	changed := false
	for _, p := range patches {
		if has(data, p.offset, p.replacement) {
			fmt.Printf("already patched: %s\n", p.name)
			continue
		}
		if !has(data, p.offset, p.expected) {
			fmt.Fprintf(os.Stderr, "unexpected bytes for %s at 0x%x\n", p.name, p.offset)
			os.Exit(1)
		}
		copy(data[p.offset:], p.replacement)
		changed = true
		fmt.Printf("patched: %s at 0x%x\n", p.name, p.offset)
	}
	if changed {
		if err := os.WriteFile(path, data, 0o644); err != nil {
			panic(err)
		}
	}
}
