package main

import (
	"bufio"
	"fmt"
	"os"
	"strings"
)

const requestID = "perm-notifications-1"

func main() {
	fmt.Println("linux payload needs notification access")
	fmt.Println(`ARCHPHENE_BRIDGE_JSON {"id":"perm-notifications-1","type":"permission.request","permission":"android.permission.POST_NOTIFICATIONS","reason":"desktop notifications"}`)
	fmt.Println("linux payload is waiting for the Android bridge response on stdin")
	os.Stdout.Sync()

	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil {
		fmt.Printf("linux payload bridge response error: %v\n", err)
		os.Exit(2)
	}

	line = strings.TrimSpace(line)
	fmt.Println("linux payload received bridge response: " + line)
	if !strings.Contains(line, `"id":"`+requestID+`"`) {
		fmt.Println("linux payload permission decision: mismatched response id")
		os.Exit(3)
	}
	if strings.Contains(line, `"granted":true`) {
		fmt.Println("linux payload permission decision: granted")
		return
	}
	fmt.Println("linux payload permission decision: denied")
}
