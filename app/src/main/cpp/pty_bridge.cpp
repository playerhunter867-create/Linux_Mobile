#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <vector>
#include <string>

struct Session { int master=-1; pid_t pid=-1; };

extern "C" JNIEXPORT jlong JNICALL
Java_org_linox_mobile_PtySession_nativeStart(JNIEnv* env, jclass, jstring jcmd, jobjectArray jargs, jobjectArray jenv, jstring jcwd) {
    const char* cmd = env->GetStringUTFChars(jcmd, nullptr);
    const char* cwd = env->GetStringUTFChars(jcwd, nullptr);
    std::vector<std::string> args;
    jsize argc = env->GetArrayLength(jargs);
    for (jsize i=0;i<argc;i++) { auto s=(jstring)env->GetObjectArrayElement(jargs,i); const char* p=env->GetStringUTFChars(s,nullptr); args.emplace_back(p); env->ReleaseStringUTFChars(s,p); env->DeleteLocalRef(s); }
    std::vector<std::string> envs;
    jsize ec = env->GetArrayLength(jenv);
    for (jsize i=0;i<ec;i++) { auto s=(jstring)env->GetObjectArrayElement(jenv,i); const char* p=env->GetStringUTFChars(s,nullptr); envs.emplace_back(p); env->ReleaseStringUTFChars(s,p); env->DeleteLocalRef(s); }
    int master=-1; pid_t pid=forkpty(&master,nullptr,nullptr,nullptr);
    if(pid<0){ env->ReleaseStringUTFChars(jcmd,cmd); env->ReleaseStringUTFChars(jcwd,cwd); return 0; }
    if(pid==0){ chdir(cwd); std::vector<char*> av; for(auto& s:args) av.push_back(const_cast<char*>(s.c_str())); av.push_back(nullptr); std::vector<char*> ev; for(auto& s:envs) ev.push_back(const_cast<char*>(s.c_str())); ev.push_back(nullptr); execve(cmd,av.data(),ev.data()); _exit(127); }
    env->ReleaseStringUTFChars(jcmd,cmd); env->ReleaseStringUTFChars(jcwd,cwd);
    auto* session=new Session(); session->master=master; session->pid=pid; return reinterpret_cast<jlong>(session);
}
extern "C" JNIEXPORT jint JNICALL Java_org_linox_mobile_PtySession_nativeRead(JNIEnv* env,jclass,jlong h,jbyteArray out){ auto*s=reinterpret_cast<Session*>(h); if(!s||s->master<0)return -1; jsize n=env->GetArrayLength(out); std::vector<char>b(n); ssize_t r=read(s->master,b.data(),b.size()); if(r>0)env->SetByteArrayRegion(out,0,r,reinterpret_cast<jbyte*>(b.data())); return (jint)r; }
extern "C" JNIEXPORT jint JNICALL Java_org_linox_mobile_PtySession_nativeWrite(JNIEnv* env,jclass,jlong h,jbyteArray data){ auto*s=reinterpret_cast<Session*>(h); if(!s||s->master<0)return -1; jsize n=env->GetArrayLength(data); std::vector<jbyte>b(n);env->GetByteArrayRegion(data,0,n,b.data());return (jint)write(s->master,b.data(),n); }
extern "C" JNIEXPORT void JNICALL Java_org_linox_mobile_PtySession_nativeResize(JNIEnv*,jclass,jlong h,jint rows,jint cols){auto*s=reinterpret_cast<Session*>(h);if(!s||s->master<0)return;struct winsize ws{};ws.ws_row=rows;ws.ws_col=cols;ioctl(s->master,TIOCSWINSZ,&ws);}
extern "C" JNIEXPORT void JNICALL Java_org_linox_mobile_PtySession_nativeSignal(JNIEnv*,jclass,jlong h,jint sig){auto*s=reinterpret_cast<Session*>(h);if(s&&s->pid>0)kill(-s->pid,sig);}
extern "C" JNIEXPORT jint JNICALL Java_org_linox_mobile_PtySession_nativeClose(JNIEnv*,jclass,jlong h){auto*s=reinterpret_cast<Session*>(h);if(!s)return -1;if(s->master>=0)close(s->master);if(s->pid>0)kill(s->pid,SIGHUP);int st=0;waitpid(s->pid,&st,WNOHANG);delete s;return 0;}
