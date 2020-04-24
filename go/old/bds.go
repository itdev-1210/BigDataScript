/*
	Execute BigDataScript:

	This program allows different "commands"
	Commands:
		1) default (no command)	:	Execute BDS Java package (compiler and interpreter)

		2) exec					:	Execute shell command and:
										i) Enforce maximum execution time,
										ii) redirect STDOUT and STDERR to files
										iii) show PID to stdout
										iv) write exit code to file
									Format:
										bds exec timeout file.stdout file.stderr file.exit command arguments...

		3) help					: 	Show command usage and exit

		4) kill pid             :   Send a kill signal to a process group (same as shell command "kill -- -pid")

	Examples:

	This will load, compile and execute 'myprogram.bds' (BigDataScript program)
		$ bds myprogram.bds

	This will execute "ls -al", redirect standard output and standard error
	to "out.stdout" and "out.stderr" respectively. When the command finishes, the
	exit code will be written to "out.exit"

		$ bds exec 10 out.stdout out.stderr out.exit ls -al
*/
package main

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"
	"tee"
)

// Exit codes
const EXITCODE_OK = 0
const EXITCODE_ERROR = 1
const EXITCODE_TIMEOUT = 2

// Debug mode
const DEBUG = false
const VERBOSE = true

// Command indicatinf to remove file (taskLogger file)
const CMD_REMOVE_FILE = "rm"

// Store all PID in this file
var taskLoggerFile string = ""

// store full path of this executable
var execName string = ""

// Code from ioutil
var randTempFile uint32

/*
	Main
*/
func main() {
	execName = discoverExecName()
	if DEBUG {
		log.Printf("Debug: execName:%s\n", execName)
	}

	// Are we requested to execute a command?
	if len(os.Args) > 1 {
		if os.Args[1] == "exec" {
			// Execute 'exec' command and exit
			exitCode := executeCommandArgs()
			if DEBUG {
				log.Printf("Debug: Exit code:%d\n", exitCode)
			}
			os.Exit(exitCode)
		} else if os.Args[1] == "kill" {
			// Kill a process group
			if len(os.Args) != 3 {
				usage("Invalid number of parameters for 'kill' command")
			}

			// Parse pid
			pidStr := os.Args[2]
			pid, err := strconv.Atoi(pidStr)
			if err != nil {
				log.Fatalf("Invalid PID: '%s'\n", pidStr)
			}

			// Kill and exit
			killProcessGroup(pid)
			os.Exit(0)
		} else if os.Args[1] == "test" {
			// Placeholder for tests, not to be used
			zzz()
		} else if os.Args[1] == "help" {
			// Show usage and exit
			usage("")
		}
	}

	// Execute BigDataScript.jar
	os.Exit(bigDataScript())
}

/*
	Invoke BigDataScript java program

	WARNING:
		It is assumed that BigDataScript.jar is in the same executable binary as 'bds'

		This is actually a nice hack used to distribute only one file. Since JAR files
		are actually ZIP files and ZIP files are indexed from the end of the file, you can
		append the JAR to the go binary (cat binary jar > new_binary) and you encapsulate
		both in the same file.

		Idea and implementation of this hack: Hernan Gonzalez
*/
func bigDataScript() int {
	// Create a taskLoggerFile (temp file based on pid number)
	prefix := "bds.pid." + strconv.Itoa(syscall.Getpid())
	pidTmpFile, err := tempFile(prefix)
	if err != nil {
		log.Fatal(err)
	}
	taskLoggerFile = pidTmpFile
	defer os.Remove(taskLoggerFile) // Make sure the PID file is removed

	// Append all arguments from command line
	args := []string{"java",
		"-Xmx2G",
		"-cp", execName,
		"ca.mcgill.mcb.pcingola.bigDataScript.BigDataScript"}
	args = append(args, "-pid")
	args = append(args, taskLoggerFile)
	for _, arg := range os.Args[1:] {
		args = append(args, arg)
	}

	// Execute command
	exitCode := executeCommand("java", args, 0, "", "", "")

	return exitCode
}

/*
  Returns absolute path of executing file.
  WARNING: this must be called before
  changing the current directory
*/
func discoverExecName() string {
	if DEBUG {
		log.Print("Debug: discoverExecName\n")
	}

	f := os.Args[0]
	if path.IsAbs(f) {
		return f
	}

	wd, err := os.Getwd()
	if err != nil {
		panic(fmt.Sprintf("Getwd failed: %s", err))
	}

	_, err = os.Stat(f)
	if err == nil { // relative file exists
		return path.Clean(path.Join(wd, f))
	} // not exists? lookup in path

	f2, err := exec.LookPath(f)
	if err != nil {
		panic(fmt.Sprintf("lookpath failed: %s", err))
	}

	if path.IsAbs(f2) {
		return f2
	}

	return path.Clean(path.Join(wd, f2))
}

/*
	Execute a command
	Enforce execution time limit
	Redirect stdout and stderr to files
*/
func executeCommandArgs() int {
	if DEBUG {
		log.Print("Debug: executeCommandArgs\n")
	}

	minArgs := 6

	if len(os.Args) < minArgs {
		usage("Invalid number of parameters for 'exec' command")
	}

	// Parse command line args
	cmdIdx := 2
	timeStr := os.Args[cmdIdx]
	cmdIdx = cmdIdx + 1
	outFile := os.Args[cmdIdx]
	cmdIdx = cmdIdx + 1
	errFile := os.Args[cmdIdx]
	cmdIdx = cmdIdx + 1
	exitFile := os.Args[cmdIdx]
	cmdIdx = cmdIdx + 1
	command := os.Args[cmdIdx]
	cmdIdx = cmdIdx + 1

	// Append other arguments
	args := []string{command}
	for _, arg := range os.Args[minArgs:] {
		args = append(args, arg)
	}

	// Parse time argument
	timeSecs, err := strconv.Atoi(timeStr)
	if err != nil {
		log.Fatalf("Invalid time: '%s'\n", timeStr)
	}

	// Show PID info (parent process is expecting this line first)
	fmt.Printf("%d\n", syscall.Getpid())
	os.Stdout.Sync()

	// Execute command
	return executeCommand(command, args, timeSecs, outFile, errFile, exitFile)
}

/*
	Execute a command (using arguments 'args')
	Redirect stdout to outFile    (unless file name is empty)
	Redirect stderr to errFile    (unless file name is empty)
	Write exit code to exitFile   (unless file name is empty)
	Timeout after timeout seconds (unless time is zero)
*/
func executeCommand(command string, args []string, timeSecs int, outFile, errFile, exitFile string) int {
	if DEBUG {
		log.Printf("Debug: executeCommand %s\n", command)
	}

	// Redirect all signals to channel (e.g. Ctrl-C)
	osSignal := make(chan os.Signal)

	if taskLoggerFile != "" {
		signal.Notify(osSignal) // Capture all signals
	} else {
		// Set a new process group.
		// Since we want to kill all child processes, we'll send a kill signal to this process group.
		// But we don't want to kill the calling program...
		// fmt.Fprintf(os.Stderr, "bds: setting new process group\n")
		if err := syscall.Setpgid(0, 0); err != nil {
			// During an ssh remote execution we will no be albe to do this.
			// In this case, we assume that the SSH daemon will catch the sinals
			// and kill al child processes.
			if DEBUG {
				log.Printf("Error redirecting signals: %s", err)
			}
		}
	}

	// Create command
	cmd := exec.Command(command)
	cmd.Args = args

	stdout := tee.NewTee(outFile, false)
	defer stdout.Close()
	cmd.Stdout = stdout

	// stdout, err := cmd.StdoutPipe()
	// if err != nil {
	// 	log.Fatal(err)
	// }
	//
	// // Copy to STDOUT to file (or to stdout)
	// if (outFile == "") || (outFile == "-") {
	// 	go tee(os.Stdout, stdout, false)
	// } else {
	// 	stdoutFile, err := os.Create(outFile)
	// 	if err != nil {
	// 		log.Fatal(err)
	// 	}
	// 	defer stdoutFile.Close()
	// 	go tee(stdoutFile, stdout, false)
	// }

	// stderr, err := cmd.StderrPipe()
	// if err != nil {
	// 	log.Fatal(err)
	// }
	// 
	// // Copy to STDERR to file (or to stderr)
	// if (errFile == "") || (errFile == "-") {
	// 	go tee(os.Stderr, stderr, true)
	// } else {
	// 	stderrFile, err := os.Create(errFile)
	// 	if err != nil {
	// 		log.Fatal(err)
	// 	}
	// 	defer stderrFile.Close()
	// 	go tee(stderrFile, stderr, true)
	// }

	// Start process
	err := cmd.Start()
	if err != nil {
		log.Fatal(err)
	}

	exitCode := executeCommandTimeout(cmd, timeSecs, exitFile, osSignal)

	return exitCode
}

/*
	Execute a command enforcing a timeout and writing exit status to 'exitFile'
*/
func executeCommandTimeout(cmd *exec.Cmd, timeSecs int, exitFile string, osSignal chan os.Signal) int {
	if DEBUG {
		log.Printf("Debug: executeCommandTimeout\n")
	}

	// Wait for execution to finish or timeout
	exitStr := ""
	if timeSecs <= 0 {
		timeSecs = 31536000 // Default: One year
	}

	// Create a timeout process
	// References: http://blog.golang.org/2010/09/go-concurrency-patterns-timing-out-and.html
	exitCode := make(chan string, 1)
	go execute(cmd, exitCode)

	// Wait until executions ends, timeout or OS signal
	kill := false
	run := true
	for run {
		select {
		case exitStr = <-exitCode:
			kill = false
			run = false

		case <-time.After(time.Duration(timeSecs) * time.Second):
			run = false
			kill = true
			exitStr = "Time out"
			if DEBUG {
				log.Printf("Debug: Timeout!\n")
			}

		case sig := <-osSignal:
			// Ignore some signals (e.g. "window changed")
			sigStr := sig.String()
			if sigStr != "window changed" && sigStr != "child exited" {
				if VERBOSE || DEBUG {
					log.Printf("bds: Received OS signal '%s'\n", sigStr)
				}

				kill = true
				exitStr = "Signal received"
				run = false
			}
		}
	}

	// Should we kill child process?
	if kill {
		cmd.Process.Kill()
		cmd.Process.Wait() // Reap their souls
	}

	// Write exitCode to file or show as log message
	if (exitFile != "") && (exitFile != "-") {
		writeFile(exitFile, exitStr) // Dump error to 'exitFile'
	}

	if kill {
		// Should we kill all process groups from taskLoggerFile?
		if taskLoggerFile != "" {
			taskLoggerCleanUpAll(taskLoggerFile)
		}

		// Send a SIGKILL to the process group (just in case any child process is still executing)
		syscall.Kill(0, syscall.SIGHUP) // Other options: -syscall.Getpgrp() , syscall.SIGKILL
	}

	// OK? exit value should be zero
	if exitStr == "0" {
		return EXITCODE_OK
	}

	// Timeout?
	if exitStr == "Time out" {
		return EXITCODE_TIMEOUT
	}

	return EXITCODE_ERROR
}

/*
	Execute a command and writing exit status to 'exitCode'
*/
func execute(cmd *exec.Cmd, exitCode chan string) {
	if DEBUG {
		log.Printf("Debug: execute\n")
	}

	// Wait for command to finish
	if err := cmd.Wait(); err != nil {
		exitCode <- err.Error()
	} 

	exitCode <- "0"
}

// Does the file exist?
func fileExists(name string) bool {
	f, err := os.OpenFile(name, os.O_RDWR|os.O_CREATE|os.O_EXCL, 0600)
	defer f.Close()
	return os.IsExist(err)
}

// Loads configuration as map, expects key=val syntax.
// Blank lines, and lines beggining with # are ignored.
func LoadConfig(filename string, dest map[string]string) {
	if DEBUG {
		log.Printf("Debug: LoadConfig(%s)\n", filename)
	}

	re, _ := regexp.Compile("[#].*\\n|\\s+\\n|\\S+[=]|.*\n")
	fi, err := os.Stat(filename)
	if err != nil {
		return
	}
	f, err := os.Open(filename)
	if err != nil {
		return
	}
	buff := make([]byte, fi.Size())
	f.Read(buff)
	f.Close()
	str := string(buff) + "\n"
	s2 := re.FindAllString(str, -1)
	for i := 0; i < len(s2); {
		if strings.HasPrefix(s2[i], "#") {
			i++
		} else if strings.HasSuffix(s2[i], "=") {
			key := strings.ToLower(s2[i])[0 : len(s2[i])-1]
			i++
			if strings.HasSuffix(s2[i], "\n") {
				val := s2[i][0 : len(s2[i])-1]
				if strings.HasSuffix(val, "\r") {
					val = val[0 : len(val)-1]
				}
				i++
				dest[key] = val
			}
		} else if strings.Index(" \t\r\n", s2[i][0:1]) > -1 {
			i++
		} else {
			//
		}
	}
}

/*
	Perform final clean up: Parse taskLoggerFile
		i) Send kill signal to all process groups that have not been marked as 'finished'
		ii) Run commands neede to deallocate processes (e.g. cluster)
		iii) Remove stale output files form unfinshed tasks

	File format:
		"pid \t {+,-} \n"

	where '+' inidicates the process was started and '-' that
	the process finished. So all pid that do not have a '-' entry
	must be killed.
*/
func taskLoggerCleanUpAll(taskLoggerFile string) {
	if DEBUG {
		log.Printf("Debug: taskLoggerCleanUpAll\n")
	}

	var (
		err  error
		line string
		file *os.File
	)

	defer os.Remove(taskLoggerFile) // Make sure the PID file is removed

	//---
	// Open file and parse it
	//---
	pids := make(map[string]bool)
	cmds := make(map[string]string)

	if file, err = os.Open(taskLoggerFile); err != nil {
		fmt.Fprintf(os.Stderr, "bds: cannot open TaskLogger file '%s' (%d)\n", taskLoggerFile, syscall.Getpid())
		return
	}
	defer file.Close() // Make sure the file is deleted

	// Read line by line
	if DEBUG {
		log.Printf("Debug: taskLoggerCleanUpAll. Parsing process pid file '%s'\n", taskLoggerFile)
	}
	reader := bufio.NewReader(file)
	for {
		if line, err = readLine(reader); err != nil {
			break
		}
		recs := strings.Split(line, "\t")

		pid := recs[0]
		addDel := recs[1]
		if DEBUG {
			log.Printf("Debug: taskLoggerCleanUpAll. \t\tpid: '%s'\tadd/del: '%s'\n", pid, addDel)
		}

		// Add or remove from map
		if addDel == "-" {
			delete(pids, pid)
		} else {
			pids[pid] = true
			if len(recs) > 2 && len(recs[2]) > 0 {
				cmds[pid] = recs[2]
			}
		}
	}

	// Kill all pending processes
	runCmds := make(map[string]string)
	for pid, running := range pids {

		// Is it marked as running? Kill it
		if running {
			if cmd, ok := cmds[pid]; !ok {
				if VERBOSE {
					log.Printf("bds: Killing PID '%s'\n", pid)
				}
				pidInt, _ := strconv.Atoi(pid)
				killProcessGroup(pidInt) // No need to run a command, just kill local porcess group
			} else if cmd == CMD_REMOVE_FILE {
				// This is a file to be removed, not a command
				if VERBOSE {
					log.Printf("bds: Deleting file '%s'\n", pid)
				}
				os.Remove(pid)
			} else {
				if DEBUG {
					log.Printf("Killing PID '%s' using command '%s'\n", pid, runCmds[cmd])
				}

				// Create command to be executed
				if _, ok = runCmds[cmd]; ok {
					runCmds[cmd] = runCmds[cmd] + "\t" + pid
				} else {
					runCmds[cmd] = cmd + "\t" + pid
				}
			}
		} else {
			if DEBUG {
				log.Printf("Debug: taskLoggerCleanUpAll. Not killing PID '%s' (finishde running)\n", pid)
			}
		}
	}

	// Run all commands (usually it's only one command)
	for cmd, args := range runCmds {
		if len(cmd) > 0 {
			// fmt.Fprintf(os.Stderr, "\t\trunning command '%s'\n", cmd)
			if VERBOSE {
				log.Printf("bds: Running command '%s'\n", cmd)
			}
			cmdExec := exec.Command(cmd)
			cmdExec.Args = strings.Split(args, "\t")
			err := cmdExec.Run()
			if err != nil {
				log.Fatal(err)
			}
		}
	}
}

/*
	Kill a process group
*/
func killProcessGroup(pid int) {
	if DEBUG {
		log.Printf("Debug: killProcessGroup( %d )\n", pid)
	}

	syscall.Kill(-pid, syscall.SIGHUP)
}

// Create a new seed for random numbers
// Code from ioutil
func randSeed() uint32 {
	return uint32(time.Now().UnixNano() + int64(os.Getpid()))
}

/*
	Read a line from a file
*/
func readLine(reader *bufio.Reader) (line string, err error) {
	var part []byte
	var prefix bool

	buffer := bytes.NewBuffer(make([]byte, 0))
	for {
		if part, prefix, err = reader.ReadLine(); err != nil {
			break
		}
		buffer.Write(part)
		if !prefix {
			line = buffer.String()
			return
		}
	}
	return
}

//
// tee: Copy to file AND stdout (or stderr)
//
// This code adapted from io.Copy function
//		http://golang.org/src/pkg/io/io.go?s=11569:11629#L338
//
// Tee: Copies from one file to another, but also prints
// to stdout/stderr
//
// ---
//
// Original comments:
// Copy copies from src to dst until either EOF is reached
// on src or an error occurs.  It returns the number of bytes
// copied and the first error encountered while copying, if any.
//
// A successful Copy returns err == nil, not err == EOF.
// Because Copy is defined to read from src until EOF, it does
// not treat an EOF from Read as an error to be reported.
//
// If dst implements the ReaderFrom interface,
// the copy is implemented by calling dst.ReadFrom(src).
// Otherwise, if src implements the WriterTo interface,
// the copy is implemented by calling src.WriteTo(dst).
func tee(dst io.Writer, src io.Reader, useStdErr bool) (written int64, err error) {
	if DEBUG {
		log.Printf("Debug: tee\n")
	}

	log.Printf("Tee\n")
	buf := make([]byte, 32*1024)
	for {
		nr, er := src.Read(buf)
		log.Printf("Tee reading:\t err: %s\tstring: '%s'\n", er, buf[0:nr])
		if nr > 0 {
			log.Printf("Tee reading '%s'\n", buf[0:nr])
			nw, ew := dst.Write(buf[0:nr])
			if nw > 0 {
				written += int64(nw)

				// Also write to stdout / stderr
				if useStdErr {
					if dst != os.Stderr { // Don't copy twice
						os.Stderr.Write(buf[0:nr])
					}
				} else {
					if dst != os.Stdout { // Don't copy twice
						os.Stdout.Write(buf[0:nr])
					}
				}
			}
			if ew != nil {
				log.Printf("Tee writing error: %s\n", ew)
				err = ew
				break
			}
			if nr != nw {
				log.Printf("Tee writing error: ShortWrite!\n")
				err = io.ErrShortWrite
				break
			}
		}
		if er == io.EOF {
			log.Printf("Tee reading EOF\n")
			break
		}
		if er != nil {
			log.Printf("Tee reading error: %s\n", er)
			err = er
			break
		}
	}
	return written, err
}

//
// Create a temp file. Retun file name instead of file descriptor
//
// Code adapted from ioutil.TempFile
// Ref: http://golang.org/src/pkg/io/ioutil/tempfile.go
//
// TempFile creates a new temporary file in the directory dir
// with a name beginning with prefix, opens the file for reading
// and writing, and returns the resulting *os.File.
// If dir is the empty string, TempFile uses the default directory
// for temporary files (see os.TempDir).
// Multiple programs calling TempFile simultaneously
// will not choose the same file.  The caller can use f.Name()
// to find the name of the file.  It is the caller's responsibility to
// remove the file when no longer needed.
func tempFile(prefix string) (name string, err error) {
	if DEBUG {
		log.Printf("Debug: tempFile\n")
	}

	name = prefix

	// Is just the prefix OK?
	if !fileExists(prefix) {
		return
	}

	nconflict := 0
	for i := 0; i < 10000; i++ {
		name = prefix + "." + tempFileNextSuffix()
		if fileExists(name) {
			if nconflict++; nconflict > 10 {
				randTempFile = randSeed()
			}
			continue
		}
		break
	}
	return
}

//  from ioutil
func tempFileNextSuffix() string {
	r := randTempFile
	if r == 0 {
		r = randSeed()
	}
	r = r*1664525 + 1013904223 // constants from Numerical Recipes
	randTempFile = r
	return strconv.Itoa(int(1e9 + r%1e9))[1:]
}

/*
	Show usage message and exit
*/
func usage(msg string) {
	if msg != "" {
		fmt.Fprintf(os.Stderr, "Error: %s\n", msg)
		fmt.Fprintf(os.Stderr, "Arguments:\n")
		for n, arg := range os.Args[1:] {
			fmt.Fprintf(os.Stderr, "\t%d : %s\n", n, arg)
		}
		fmt.Fprintf(os.Stderr, "\n")
	}

	// Show help and exit
	fmt.Fprintf(os.Stderr, "Usage: bds command\n\n")
	fmt.Fprintf(os.Stderr, "Commands:\n\n")
	fmt.Fprintf(os.Stderr, "  default :  Execute BigDataScript Java program (compiler and interpreter)\n")
	fmt.Fprintf(os.Stderr, "             Syntax:\n")
	fmt.Fprintf(os.Stderr, "                 bds [options] program.bds\n\n")
	fmt.Fprintf(os.Stderr, "  exec    :  Execute shell scripts and:\n")
	fmt.Fprintf(os.Stderr, "                 i) Show pid.\n")
	fmt.Fprintf(os.Stderr, "                 ii) Enforce maimum execution time.\n")
	fmt.Fprintf(os.Stderr, "                 iii) Redirect STDOUT and STDERR to files.\n")
	fmt.Fprintf(os.Stderr, "                 iv) Write exitCode to a file.\n")
	fmt.Fprintf(os.Stderr, "             Note: If any file name is '-' it is ignored (not redirected).\n")
	fmt.Fprintf(os.Stderr, "             Syntax:\n")
	fmt.Fprintf(os.Stderr, "                 bds exec timeout file.stdout file.stderr file.exit command arguments...\n\n")
	fmt.Fprintf(os.Stderr, "  kill pid :  Kill process group 'pid'.\n")
	os.Exit(1)
}

/*
	Write a string to a file
*/
func writeFile(fileName, message string) {
	file, err := os.Create(fileName)
	if err != nil {
		log.Fatal(err)
	}
	defer file.Close()
	file.WriteString(message)
}

// A function used for testing
func zzz() {
	fmt.Printf("Test function: Zzz\n")
	os.Exit(1)
}
