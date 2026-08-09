#include <jni.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <stdlib.h>

#include <cerrno>
#include <string>
#include <vector>
#include <atomic>

struct Session {
    std::atomic<int> master{-1};
    std::atomic<pid_t> pid{-1};
};

static void releaseUtf(JNIEnv* env, jstring value, const char* ptr) {
    if (value && ptr) env->ReleaseStringUTFChars(value, ptr);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_linox_mobile_PtySession_nativeStart(
        JNIEnv* env, jclass, jstring jcmd, jobjectArray jargs,
        jobjectArray jenv, jstring jcwd) {
    if (!jcmd || !jargs || !jenv || !jcwd) return 0;

    const char* cmd = env->GetStringUTFChars(jcmd, nullptr);
    const char* cwd = env->GetStringUTFChars(jcwd, nullptr);
    if (!cmd || !cwd) {
        releaseUtf(env, jcmd, cmd);
        releaseUtf(env, jcwd, cwd);
        return 0;
    }

    std::vector<std::string> args;
    const jsize argc = env->GetArrayLength(jargs);
    args.reserve(static_cast<size_t>(argc));
    for (jsize i = 0; i < argc; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        if (!value) continue;
        const char* text = env->GetStringUTFChars(value, nullptr);
        if (text) {
            args.emplace_back(text);
            env->ReleaseStringUTFChars(value, text);
        }
        env->DeleteLocalRef(value);
    }

    std::vector<std::string> envs;
    const jsize envc = env->GetArrayLength(jenv);
    envs.reserve(static_cast<size_t>(envc));
    for (jsize i = 0; i < envc; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(jenv, i));
        if (!value) continue;
        const char* text = env->GetStringUTFChars(value, nullptr);
        if (text) {
            envs.emplace_back(text);
            env->ReleaseStringUTFChars(value, text);
        }
        env->DeleteLocalRef(value);
    }

    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        if (master >= 0) close(master);
        releaseUtf(env, jcmd, cmd);
        releaseUtf(env, jcwd, cwd);
        return 0;
    }

    char* slaveName = ptsname(master);
    if (!slaveName) {
        close(master);
        releaseUtf(env, jcmd, cmd);
        releaseUtf(env, jcwd, cwd);
        return 0;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        releaseUtf(env, jcmd, cmd);
        releaseUtf(env, jcwd, cwd);
        return 0;
    }

    if (pid == 0) {
        if (setsid() < 0) _exit(127);

        int slave = open(slaveName, O_RDWR | O_NOCTTY);
        if (slave < 0) _exit(127);

        if (ioctl(slave, TIOCSCTTY, 0) != 0) _exit(127);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);

        if (slave > STDERR_FILENO) close(slave);
        close(master);

        if (chdir(cwd) != 0) _exit(127);

        std::vector<char*> argv;
        argv.reserve(args.size() + 1);
        for (auto& value : args) {
            argv.push_back(const_cast<char*>(value.c_str()));
        }
        argv.push_back(nullptr);

        std::vector<char*> envp;
        envp.reserve(envs.size() + 1);
        for (auto& value : envs) {
            envp.push_back(const_cast<char*>(value.c_str()));
        }
        envp.push_back(nullptr);

        execve(cmd, argv.data(), envp.data());
        _exit(127);
    }

    releaseUtf(env, jcmd, cmd);
    releaseUtf(env, jcwd, cwd);

    auto* session = new Session();
    session->master = master;
    session->pid = pid;
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_linox_mobile_PtySession_nativeRead(
        JNIEnv* env, jclass, jlong handle, jbyteArray out) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || session->master.load() < 0 || !out) return -1;

    const jsize capacity = env->GetArrayLength(out);
    if (capacity <= 0) return 0;

    std::vector<jbyte> buffer(static_cast<size_t>(capacity));
    const int master = session->master.load();
    if (master < 0) return -1;

    const ssize_t result = read(master, buffer.data(), buffer.size());
    if (result > 0) {
        env->SetByteArrayRegion(
            out, 0, static_cast<jsize>(result), buffer.data()
        );
    }
    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_linox_mobile_PtySession_nativeWrite(
        JNIEnv* env, jclass, jlong handle, jbyteArray data) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || session->master.load() < 0 || !data) return -1;

    const jsize size = env->GetArrayLength(data);
    std::vector<jbyte> buffer(static_cast<size_t>(size));
    env->GetByteArrayRegion(data, 0, size, buffer.data());

    size_t written = 0;
    while (written < static_cast<size_t>(size)) {
        const int master = session->master.load();
        if (master < 0) return written ? static_cast<jint>(written) : -1;

        const ssize_t n = write(
            master, buffer.data() + written, size - written
        );
        if (n > 0) {
            written += static_cast<size_t>(n);
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        return written ? static_cast<jint>(written) : -1;
    }

    return static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL
Java_org_linox_mobile_PtySession_nativeResize(
        JNIEnv*, jclass, jlong handle, jint rows, jint cols) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session || session->master.load() < 0) return;

    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    ioctl(session->master.load(), TIOCSWINSZ, &ws);
}

extern "C" JNIEXPORT void JNICALL
Java_org_linox_mobile_PtySession_nativeSignal(
        JNIEnv*, jclass, jlong handle, jint sig) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session && session->pid.load() > 0) {
        kill(-session->pid.load(), sig);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_linox_mobile_PtySession_nativeClose(
        JNIEnv*, jclass, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (!session) return -1;

    const int master = session->master.exchange(-1);
    if (master >= 0) close(master);

    const pid_t pid = session->pid.exchange(-1);
    if (pid > 0) {
        kill(-pid, SIGHUP);
        waitpid(pid, nullptr, WNOHANG);
    }

    // Do not free Session here: nativeRead may still be blocked in another
    // thread. This avoids a JNI use-after-free during PTY shutdown.
    return 0;
}
