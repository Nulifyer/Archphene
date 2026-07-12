package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

const requestID = "file-open-text-1"

func main() {
	fmt.Println("linux payload needs user-selected text document")
	fmt.Println(`ARCHPHENE_BRIDGE_JSON {"id":"file-open-text-1","type":"file.open_document","mime":"text/plain","reason":"open user selected text document"}`)
	fmt.Println("linux payload is waiting for the Android file portal response on stdin")
	os.Stdout.Sync()

	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil {
		fmt.Printf("linux payload file portal response error: %v\n", err)
		os.Exit(2)
	}

	line = strings.TrimSpace(line)
	fmt.Println("linux payload received file portal response: " + line)
	if !strings.Contains(line, `"id":"`+requestID+`"`) {
		fmt.Println("linux payload file portal decision: mismatched response id")
		os.Exit(3)
	}
	if strings.Contains(line, `"granted":true`) {
		fmt.Println("linux payload file portal decision: granted")
		return
	}
	fmt.Println("linux payload file portal decision: denied")
}
