package main

import (
	"fmt"
	"runtime"
)

func main() {
	fmt.Printf("hello from linux elf\n")
	fmt.Printf("goos=%s goarch=%s\n", runtime.GOOS, runtime.GOARCH)
}
