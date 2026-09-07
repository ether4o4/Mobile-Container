// pty.cpp - JNI bridge for an interactive pseudo-terminal shell on Android.
// Spawns a child process (busybox sh / system sh) attached to a pty, returns
// the master fd to Java, and exposes read/write/resize/wait.
#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <pty.h>
#include <vector>
#include <string>

#define TAG "MCPTY"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct Pty {
    int master_fd;
    pid_t pid;
};

static std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    env->ReleaseStringUTFChars(s, c);
    return r;
}

static std::vector<char*> split_args(const std::string &cmd) {
    std::vector<char*> args;
    std::string cur;
    bool inq = false;
    for (size_t i = 0; i < cmd.size(); i++) {
        char c = cmd[i];
        if (c == '"') { inq = !inq; continue; }
        if ((c == ' ' || c == '\t') && !inq) {
            if (!cur.empty()) { args.push_back(strdup(cur.c_str())); cur.clear(); }
        } else {
            cur += c;
        }
    }
    if (!cur.empty()) args.push_back(strdup(cur.c_str()));
    args.push_back(nullptr);
    return args;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ether4o4_mobilecontainer_terminal_TerminalBridge_nativeSpawn(
    JNIEnv *env, jclass, jstring jcmd, jstring jcwd, jobjectArray jenv,
    jint rows, jint cols) {

    std::string cmd = jstr(env, jcmd);
    std::string cwd = jstr(env, jcwd);
    if (cmd.empty()) cmd = "/system/bin/sh";

    int masterfd = -1;
    pid_t pid = forkpty(&masterfd, nullptr, nullptr, nullptr);
    if (pid < 0) {
        LOGE("forkpty failed");
        return -1;
    }
    if (pid == 0) {
        // child
        if (!cwd.empty()) {
            chdir(cwd.c_str());
        }
        // set up environment
        if (jenv != nullptr) {
            jsize n = env->GetArrayLength(jenv);
            for (jsize i = 0; i < n; i++) {
                jstring e = (jstring) env->GetObjectArrayElement(jenv, i);
                if (e) {
                    std::string kv = jstr(env, e);
                    // kv is KEY=VAL
                    size_t eq = kv.find('=');
                    if (eq != std::string::npos) {
                        setenv(kv.substr(0, eq).c_str(), kv.substr(eq + 1).c_str(), 1);
                    }
                    env->DeleteLocalRef(e);
                }
            }
        }
        setenv("TERM", "xterm-256color", 1);
        setenv("PATH", "/system/bin:/system/xbin:/data/data/com.ether4o4.mobilecontainer/files/usr/bin:/data/data/com.ether4o4.mobilecontainer/files/bin", 1);
        setenv("HOME", "/data/data/com.ether4o4.mobilecontainer/files", 1);
        // window size
        if (rows > 0 && cols > 0) {
            struct winsize ws;
            memset(&ws, 0, sizeof(ws));
            ws.ws_row = rows;
            ws.ws_col = cols;
            ioctl(0, TIOCSWINSZ, &ws);
        }
        // build argv: split cmd
        auto args = split_args(cmd);
        execvp(args[0], args.data());
        // if exec fails, try /system/bin/sh -c
        char *fallback[] = {(char*)"/system/bin/sh", (char*)"-c", (char*)cmd.c_str(), nullptr};
        execv("/system/bin/sh", fallback);
        _exit(127);
    }

    // parent
    Pty *p = new Pty{masterfd, pid};
    LOGI("spawned pid=%d masterfd=%d cmd=%s", pid, masterfd, cmd.c_str());
    return (jlong)(intptr_t)p;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ether4o4_mobilecontainer_terminal_TerminalBridge_nativeRead(
    JNIEnv *env, jclass, jlong handle, jbyteArray buf, jint off, jint len) {
    Pty *p = (Pty*)(intptr_t)handle;
    if (!p || p->master_fd < 0) return -1;
    std::vector<char> tmp(len);
    ssize_t n = read(p->master_fd, tmp.data(), len);
    if (n <= 0) return (jint)n;
    env->SetByteArrayRegion(buf, off, (jint)n, (const jbyte*)tmp.data());
    return (jint)n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ether4o4_mobilecontainer_terminal_TerminalBridge_nativeWrite(
    JNIEnv *env, jclass, jlong handle, jbyteArray buf, jint off, jint len) {
    Pty *p = (Pty*)(intptr_t)handle;
    if (!p || p->master_fd < 0) return -1;
    std::vector<char> tmp(len);
    env->GetByteArrayRegion(buf, off, len, (jbyte*)tmp.data());
    ssize_t n = write(p->master_fd, tmp.data(), len);
    return (jint)n;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ether4o4_mobilecontainer_terminal_TerminalBridge_nativeResize(
    JNIEnv *env, jclass, jlong handle, jint rows, jint cols) {
    Pty *p = (Pty*)(intptr_t)handle;
    if (!p || p->master_fd < 0) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = rows;
    ws.ws_col = cols;
    ioctl(p->master_fd, TIOCSWINSZ, &ws);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ether4o4_mobilecontainer_terminal_TerminalBridge_nativeWait(
    JNIEnv *env, jclass, jlong handle) {
    Pty *p = (Pty*)(intptr_t)handle;
    if (!p || p->pid <= 0) return -1;
    int status = 0;
    waitpid(p->pid, &status, 0);
    return (jint)status;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ether4o4_mobilecontainer_terminal_TerminalBridge_nativeClose(
    JNIEnv *env, jclass, jlong handle) {
    Pty *p = (Pty*)(intptr_t)handle;
    if (!p) return;
    if (p->master_fd >= 0) {
        close(p->master_fd);
        p->master_fd = -1;
    }
    if (p->pid > 0) {
        kill(p->pid, SIGHUP);
        int status = 0;
        // non-blocking reap, then force
        if (waitpid(p->pid, &status, WNOHANG) == 0) {
            kill(p->pid, SIGKILL);
            waitpid(p->pid, &status, 0);
        }
        p->pid = 0;
    }
    delete p;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ether4o4_mobilecontainer_terminal_TerminalBridge_nativeSendSignal(
    JNIEnv *env, jclass, jlong handle, jint sig) {
    Pty *p = (Pty*)(intptr_t)handle;
    if (!p || p->pid <= 0) return -1;
    // send to process group so children get it too
    kill(-p->pid, sig);
    return 0;
}
