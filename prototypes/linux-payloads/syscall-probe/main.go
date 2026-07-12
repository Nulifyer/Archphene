package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

const (
	sysOpen                  = 2
	sysPipe                  = 22
	sysAccess                = 21
	sysRtSigaction           = 13
	sysRtSigprocmask         = 14
	sysSetitimer             = 38
	sysExecve                = 59
	sysUname                 = 63
	sysPrctl                 = 157
	sysArchPrctl             = 158
	sysFutex                 = 202
	sysSchedSetaffinity      = 203
	sysSchedGetaffinity      = 204
	sysSetTidAddress         = 218
	sysEpollCreate           = 213
	sysInotifyInit           = 253
	sysMkdir                 = 83
	sysOpenat                = 257
	sysMkdirat               = 258
	sysUnlinkat              = 263
	sysRenameat              = 264
	sysReadlinkat            = 267
	sysFaccessat             = 269
	sysSetRobustList         = 273
	sysEventfd               = 284
	sysEventfd2              = 290
	sysEpollCreate1          = 291
	sysPipe2                 = 293
	sysInotifyInit1          = 294
	sysPrlimit64             = 302
	sysGetcpu                = 309
	sysGetrandom             = 318
	sysMemfdCreate           = 319
	sysMembarrier            = 324
	sysStatx                 = 332
	sysRseq                  = 334
	sysIoUringSetup          = 425
	sysPidfdOpen             = 434
	sysClone3                = 435
	sysOpenat2               = 437
	sysFaccessat2            = 439
	sysLandlockCreateRuleset = 444
	sysFutexWaitv            = 449
	atFdcwd                  = ^uintptr(99)
	openReadOnly             = 0
	accessExists             = 0
	membarrierQuery          = 0
)

func main() {
	if len(os.Args) != 2 {
		fmt.Println("usage: syscall-probe <name>")
		fmt.Println("names: open, access, openat, openat2, mkdir, mkdirat, unlinkat, renameat, readlinkat, faccessat, faccessat2, newfstatat, statx, getrandom, memfd_create, membarrier, rt_sigaction, rt_sigprocmask, setitimer, execve_null, uname, futex, sched_setaffinity, sched_getaffinity, getcpu, arch_prctl, set_tid_address, prctl, set_robust_list, prlimit64, rseq, io_uring_setup, clone3, pidfd_open, landlock_create_ruleset, futex_waitv")
		os.Exit(2)
	}

	name := os.Args[1]
	fmt.Printf("probe=%s before\n", name)

	var r1 uintptr
	var errno syscall.Errno
	workBase := os.Getenv("TMPDIR")
	if workBase == "" {
		workBase = os.Getenv("HOME")
	}
	if workBase == "" {
		workBase = "/tmp"
	}
	probePath := cstr(workBase)
	tmpDir := cstr(workBase + "/archphene-syscall-probe-dir")
	tmpA := cstr(workBase + "/archphene-syscall-probe-a")
	tmpB := cstr(workBase + "/archphene-syscall-probe-b")
	procSelfExe := cstr("/proc/self/exe")
	memfdName := cstr("archphene-probe")
	random := []byte{0, 0, 0, 0}

	switch name {
	case "open":
		r1, _, errno = syscall.RawSyscall(sysOpen, ptr(probePath), openReadOnly, 0)
		closeIfOK(r1, errno)
	case "access":
		r1, _, errno = syscall.RawSyscall(sysAccess, ptr(probePath), accessExists, 0)
	case "pipe":
		var fds [2]int32
		r1, _, errno = syscall.RawSyscall(sysPipe, uintptr(unsafe.Pointer(&fds[0])), 0, 0)
	case "pipe2":
		var fds [2]int32
		r1, _, errno = syscall.RawSyscall(sysPipe2, uintptr(unsafe.Pointer(&fds[0])), 0, 0)
	case "eventfd":
		r1, _, errno = syscall.RawSyscall(sysEventfd, 0, 0, 0)
		closeIfOK(r1, errno)
	case "eventfd2":
		r1, _, errno = syscall.RawSyscall(sysEventfd2, 0, 0, 0)
		closeIfOK(r1, errno)
	case "inotify_init":
		r1, _, errno = syscall.RawSyscall(sysInotifyInit, 0, 0, 0)
		closeIfOK(r1, errno)
	case "inotify_init1":
		r1, _, errno = syscall.RawSyscall(sysInotifyInit1, 0, 0, 0)
		closeIfOK(r1, errno)
	case "epoll_create":
		r1, _, errno = syscall.RawSyscall(sysEpollCreate, 1, 0, 0)
		closeIfOK(r1, errno)
	case "epoll_create1":
		r1, _, errno = syscall.RawSyscall(sysEpollCreate1, 0, 0, 0)
		closeIfOK(r1, errno)
	case "openat":
		r1, _, errno = syscall.RawSyscall6(sysOpenat, atFdcwd, ptr(probePath), openReadOnly, 0, 0, 0)
		closeIfOK(r1, errno)
	case "openat2":
		r1, _, errno = syscall.RawSyscall6(sysOpenat2, atFdcwd, ptr(probePath), 0, 0, 0, 0)
	case "mkdir":
		r1, _, errno = syscall.RawSyscall(sysMkdir, ptr(tmpDir), 0700, 0)
	case "mkdirat":
		r1, _, errno = syscall.RawSyscall6(sysMkdirat, atFdcwd, ptr(tmpDir), 0700, 0, 0, 0)
	case "unlinkat":
		r1, _, errno = syscall.RawSyscall(sysUnlinkat, atFdcwd, ptr(tmpA), 0)
	case "renameat":
		r1, _, errno = syscall.RawSyscall6(sysRenameat, atFdcwd, ptr(tmpA), atFdcwd, ptr(tmpB), 0, 0)
	case "readlinkat":
		buf := make([]byte, 512)
		r1, _, errno = syscall.RawSyscall6(sysReadlinkat, atFdcwd, ptr(procSelfExe), uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)), 0, 0)
	case "faccessat":
		r1, _, errno = syscall.RawSyscall6(sysFaccessat, atFdcwd, ptr(probePath), accessExists, 0, 0, 0)
	case "faccessat2":
		r1, _, errno = syscall.RawSyscall6(sysFaccessat2, atFdcwd, ptr(probePath), accessExists, 0, 0, 0)
	case "newfstatat":
		var stat syscall.Stat_t
		r1, _, errno = syscall.RawSyscall6(syscall.SYS_NEWFSTATAT, atFdcwd, ptr(probePath), uintptr(unsafe.Pointer(&stat)), 0, 0, 0)
	case "statx":
		var statx [256]byte
		r1, _, errno = syscall.RawSyscall6(sysStatx, atFdcwd, ptr(probePath), 0, 0xfff, uintptr(unsafe.Pointer(&statx[0])), 0)
	case "getrandom":
		r1, _, errno = syscall.RawSyscall(sysGetrandom, uintptr(unsafe.Pointer(&random[0])), uintptr(len(random)), 0)
	case "memfd_create":
		r1, _, errno = syscall.RawSyscall(sysMemfdCreate, ptr(memfdName), 0, 0)
		closeIfOK(r1, errno)
	case "membarrier":
		r1, _, errno = syscall.RawSyscall(sysMembarrier, membarrierQuery, 0, 0)
	case "rt_sigaction":
		r1, _, errno = syscall.RawSyscall6(sysRtSigaction, 1, 0, 0, 8, 0, 0)
	case "rt_sigprocmask":
		r1, _, errno = syscall.RawSyscall6(sysRtSigprocmask, 0, 0, 0, 8, 0, 0)
	case "setitimer":
		r1, _, errno = syscall.RawSyscall(sysSetitimer, 0, 0, 0)
	case "execve_null":
		r1, _, errno = syscall.RawSyscall(sysExecve, 0, 0, 0)
	case "uname":
		buf := make([]byte, 390)
		r1, _, errno = syscall.RawSyscall(sysUname, uintptr(unsafe.Pointer(&buf[0])), 0, 0)
	case "futex":
		word := uint32(0)
		r1, _, errno = syscall.RawSyscall6(sysFutex, uintptr(unsafe.Pointer(&word)), 1, 1, 0, 0, 0)
	case "sched_setaffinity":
		r1, _, errno = syscall.RawSyscall(sysSchedSetaffinity, 0, 0, 0)
	case "sched_getaffinity":
		mask := make([]byte, 128)
		r1, _, errno = syscall.RawSyscall(sysSchedGetaffinity, 0, uintptr(len(mask)), uintptr(unsafe.Pointer(&mask[0])))
	case "getcpu":
		var cpu uint32
		var node uint32
		r1, _, errno = syscall.RawSyscall(sysGetcpu, uintptr(unsafe.Pointer(&cpu)), uintptr(unsafe.Pointer(&node)), 0)
	case "arch_prctl":
		var fs uintptr
		r1, _, errno = syscall.RawSyscall(sysArchPrctl, 0x1003, uintptr(unsafe.Pointer(&fs)), 0)
	case "set_tid_address":
		word := int32(0)
		r1, _, errno = syscall.RawSyscall(sysSetTidAddress, uintptr(unsafe.Pointer(&word)), 0, 0)
	case "prctl":
		r1, _, errno = syscall.RawSyscall(sysPrctl, 0, 0, 0)
	case "set_robust_list":
		r1, _, errno = syscall.RawSyscall(sysSetRobustList, 0, 0, 0)
	case "prlimit64":
		r1, _, errno = syscall.RawSyscall6(sysPrlimit64, uintptr(os.Getpid()), uintptr(syscall.RLIMIT_NOFILE), 0, 0, 0, 0)
	case "rseq":
		r1, _, errno = syscall.RawSyscall6(sysRseq, 0, 0, 0, 0, 0, 0)
	case "io_uring_setup":
		r1, _, errno = syscall.RawSyscall(sysIoUringSetup, 1, 0, 0)
	case "clone3":
		r1, _, errno = syscall.RawSyscall6(sysClone3, 0, 0, 0, 0, 0, 0)
	case "pidfd_open":
		r1, _, errno = syscall.RawSyscall(sysPidfdOpen, uintptr(os.Getpid()), 0, 0)
		closeIfOK(r1, errno)
	case "landlock_create_ruleset":
		r1, _, errno = syscall.RawSyscall(sysLandlockCreateRuleset, 0, 0, 0)
	case "futex_waitv":
		r1, _, errno = syscall.RawSyscall(sysFutexWaitv, 0, 0, 0)
	default:
		fmt.Printf("unknown probe=%s\n", name)
		os.Exit(2)
	}

	fmt.Printf("probe=%s after r1=%d errno=%d\n", name, r1, errno)
}

func cstr(value string) []byte {
	return append([]byte(value), 0)
}

func ptr(value []byte) uintptr {
	return uintptr(unsafe.Pointer(&value[0]))
}

func closeIfOK(fd uintptr, errno syscall.Errno) {
	if errno == 0 {
		_ = syscall.Close(int(fd))
	}
}
