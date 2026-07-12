package main

import (
    "fmt"
    "os"
)

var names = map[uint32]string{
    0: "read", 1: "write", 2: "open", 3: "close", 4: "stat", 5: "fstat", 6: "lstat", 8: "lseek", 9: "mmap", 10: "mprotect", 11: "munmap", 12: "brk", 13: "rt_sigaction", 14: "rt_sigprocmask", 16: "ioctl", 17: "pread64", 20: "writev", 21: "access", 24: "sched_yield", 39: "getpid", 72: "fcntl", 78: "getdents", 79: "getcwd", 89: "readlink", 90: "chmod", 91: "fchmod", 97: "getrlimit", 99: "sysinfo", 102: "getuid", 104: "getgid", 107: "geteuid", 108: "getegid", 131: "sigaltstack", 157: "prctl", 158: "arch_prctl", 186: "gettid", 202: "futex", 217: "getdents64", 218: "set_tid_address", 231: "exit_group", 257: "openat", 262: "newfstatat", 263: "unlinkat", 264: "renameat", 273: "set_robust_list", 302: "prlimit64", 318: "getrandom", 319: "memfd_create", 324: "membarrier", 332: "statx", 334: "rseq", 425: "io_uring_setup", 434: "pidfd_open", 435: "clone3", 437: "openat2", 439: "faccessat2", 444: "landlock_create_ruleset", 449: "futex_waitv",
}

type site struct {
    off int
    sys uint32
    known bool
    pattern string
}

func u32le(b []byte) uint32 { return uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2])<<16 | uint32(b[3])<<24 }

func infer(data []byte, sysOff int) (uint32, bool, string) {
    start := sysOff - 96
    if start < 0 { start = 0 }
    for i := sysOff - 5; i >= start; i-- {
        if data[i] == 0xb8 && i+5 <= len(data) {
            return u32le(data[i+1:i+5]), true, fmt.Sprintf("mov eax at 0x%x", i)
        }
    }
    for i := sysOff - 4; i >= start; i-- {
        if i+3 <= len(data) && data[i] == 0x6a && data[i+2] == 0x58 {
            return uint32(int8(data[i+1])), true, fmt.Sprintf("push/pop rax at 0x%x", i)
        }
    }
    return 0, false, "unknown"
}

func main() {
    if len(os.Args) < 2 {
        fmt.Fprintln(os.Stderr, "usage: syscallscan <elf> [...]")
        os.Exit(2)
    }
    for _, path := range os.Args[1:] {
        data, err := os.ReadFile(path)
        if err != nil { panic(err) }
        var sites []site
        counts := map[string]int{}
        for i := 0; i+1 < len(data); i++ {
            if data[i] == 0x0f && data[i+1] == 0x05 {
                n, ok, pat := infer(data, i)
                s := site{off: i, sys: n, known: ok, pattern: pat}
                sites = append(sites, s)
                key := "unknown"
                if ok {
                    key = fmt.Sprintf("%d", n)
                    if name, found := names[n]; found { key += ":" + name }
                }
                counts[key]++
            }
        }
        fmt.Printf("%s syscall sites=%d\n", path, len(sites))
        for k, count := range counts { fmt.Printf("  %-28s %d\n", k, count) }
        fmt.Println("sites:")
        for _, s := range sites {
            label := "unknown"
            if s.known {
                label = fmt.Sprintf("%d", s.sys)
                if name, found := names[s.sys]; found { label += ":" + name }
            }
            from := s.off - 12
            if from < 0 { from = 0 }
            to := s.off + 8
            if to > len(data) { to = len(data) }
            fmt.Printf("  0x%06x %-24s %-24s bytes", s.off, label, s.pattern)
            for _, b := range data[from:to] { fmt.Printf(" %02x", b) }
            fmt.Println()
        }
        fmt.Println()
    }
}