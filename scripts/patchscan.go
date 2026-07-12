package main

import (
	"fmt"
	"os"
)

type rule struct {
	name        string
	mov         []byte
	replacement []byte
}

func matchAt(data []byte, off int, pat []byte) bool {
	if off < 0 || off+len(pat) > len(data) {
		return false
	}
	for i := range pat {
		if data[off+i] != pat[i] {
			return false
		}
	}
	return true
}

func findSyscall(data []byte, start int) int {
	limit := start + 96
	if limit > len(data)-1 {
		limit = len(data) - 1
	}
	for i := start; i < limit; i++ {
		if data[i] == 0x0f && data[i+1] == 0x05 {
			return i
		}
	}
	return -1
}

func patchFile(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	backup := path + ".orig"
	if _, err := os.Stat(backup); os.IsNotExist(err) {
		if err := os.WriteFile(backup, data, 0o644); err != nil {
			return err
		}
	}
	rules := []rule{
		{"rt_sigprocmask", []byte{0xb8, 0x0e, 0x00, 0x00, 0x00}, []byte{0x31, 0xc0}},
		{"set_robust_list", []byte{0xb8, 0x11, 0x01, 0x00, 0x00}, []byte{0x31, 0xc0}},
		{"rseq", []byte{0xb8, 0x4e, 0x01, 0x00, 0x00}, []byte{0xf7, 0xd8}},
	}
	changed := false
	counts := map[string]int{}
	for _, r := range rules {
		for i := 0; i <= len(data)-len(r.mov); i++ {
			if !matchAt(data, i, r.mov) {
				continue
			}
			sys := findSyscall(data, i+len(r.mov))
			if sys < 0 {
				continue
			}
			if data[sys] == r.replacement[0] && data[sys+1] == r.replacement[1] {
				continue
			}
			data[sys] = r.replacement[0]
			data[sys+1] = r.replacement[1]
			changed = true
			counts[r.name]++
			fmt.Printf("%s: patched %s syscall at 0x%x after mov at 0x%x\n", path, r.name, sys, i)
		}
	}
	if changed {
		if err := os.WriteFile(path, data, 0o644); err != nil {
			return err
		}
	}
	for _, r := range rules {
		fmt.Printf("%s: %s patches=%d\n", path, r.name, counts[r.name])
	}
	return nil
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: patchscan <elf> [...]")
		os.Exit(2)
	}
	for _, path := range os.Args[1:] {
		if err := patchFile(path); err != nil {
			fmt.Fprintf(os.Stderr, "%s: %v\n", path, err)
			os.Exit(1)
		}
	}
}
