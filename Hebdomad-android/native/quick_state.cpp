#include "port.h"
#include <jni.h>
#include <atomic>
#include <stdio.h>
#include <unistd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <string>
#include <vector>
#include <algorithm>
#include <GLES2/gl2.h>

// Queue requests from Android; all engine calls execute at a game-frame boundary.
// Six private slots (99..104) leave the three original slots intact.
static std::atomic<int> request{0}, result{0};
extern void port_scene_changed();
extern "C" JNIEXPORT jboolean JNICALL Java_org_hebdomad_android_GameActivity_quickState(JNIEnv*,jclass,jboolean load,jint slot){
    if(slot<0||slot>=6)return false;
    int empty=0;
    if(!request.compare_exchange_strong(empty,-1))return false;
    result.store(0);request.store(1+slot*2+(load?1:0));return true;
}
extern "C" JNIEXPORT jint JNICALL Java_org_hebdomad_android_GameActivity_quickStateResult(JNIEnv*,jclass){return result.load();}
static bool copy_file(const char *from,const char *to){
    FILE *in=fopen(from,"rb");if(!in)return false;
    FILE *out=fopen(to,"wb");if(!out){fclose(in);return false;}
    char block[8192];size_t n;bool ok=true;
    while((n=fread(block,1,sizeof block,in)))if(fwrite(block,1,n,out)!=n){ok=false;break;}
    ok=ok&&!ferror(in);if(fflush(out)||fsync(fileno(out)))ok=false;
    fclose(in);if(fclose(out))ok=false;return ok;
}
static bool save_snapshot(const std::string &filename){
    int w=0,h=0;if(!SDL_GetWindowSizeInPixels(port_window,&w,&h)||w<=0||h<=0)return false;
    const int tw=std::min(w,640),th=std::max(1,h*tw/w);
    std::vector<unsigned char> rgba(static_cast<size_t>(w)*h*4);
    GLint framebuffer,packing;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&framebuffer);glGetIntegerv(GL_PACK_ALIGNMENT,&packing);
    glBindFramebuffer(GL_FRAMEBUFFER,0);glPixelStorei(GL_PACK_ALIGNMENT,1);
    glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,rgba.data());
    GLenum error=glGetError();glPixelStorei(GL_PACK_ALIGNMENT,packing);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
    if(error!=GL_NO_ERROR)return false;
    std::vector<jint> pixels(static_cast<size_t>(tw)*th);
    // Box resolve and vertical flip: thumbnails stay sharp and upright.
    for(int y=0;y<th;y++)for(int x=0;x<tw;x++){
        unsigned r=0,g=0,b=0,n=0;
        for(int sy=y*h/th;sy<(y+1)*h/th;sy++)for(int sx=x*w/tw;sx<(x+1)*w/tw;sx++){
            const auto *p=&rgba[(static_cast<size_t>(h-1-sy)*w+sx)*4];r+=p[0];g+=p[1];b+=p[2];n++;
        }
        pixels[y*tw+x]=0xff000000u|((r/n)<<16)|((g/n)<<8)|(b/n);
    }
    auto *env=static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());if(!env)return false;
    if(env->PushLocalFrame(8)<0){env->ExceptionClear();return false;}
    jobject activity=static_cast<jobject>(SDL_GetAndroidActivity());
    jclass clazz=env->GetObjectClass(activity);
    jmethodID method=env->GetStaticMethodID(clazz,"writeStateSnapshot","(Ljava/lang/String;[III)Z");
    jintArray data=env->NewIntArray(pixels.size());jstring path=env->NewStringUTF(filename.c_str());
    bool ok=false;
    if(method&&data&&path&&!env->ExceptionCheck()){
        env->SetIntArrayRegion(data,0,pixels.size(),pixels.data());
        ok=env->CallStaticBooleanMethod(clazz,method,path,data,tw,th);
    }
    if(env->ExceptionCheck()){env->ExceptionClear();ok=false;}env->PopLocalFrame(nullptr);
    return ok;
}
void quick_state_frame(){
    int command=request.load();if(command<=0)return;
    if(!request.compare_exchange_strong(command,-2))return;
    struct Complete{~Complete(){request.store(0);}}complete;
    int action=(command-1)%2+1,slot=99+(command-1)/2;
    auto game=reinterpret_cast<uintptr_t(*)()>(0xd9a24)();
    if(!game){result.store(-1);return;}
    const int phase=*reinterpret_cast<int*>(game+0xea0);
    if(phase==2){result.store(-4);return;} // Room loading is still in progress.
    if(action==1&&phase!=4){result.store(-1);return;}
    // Build the same path as the original serializer rather than assuming a
    // platform-specific save directory. GameString uses the engine allocator.
    struct String{char *data;unsigned length;char local[16];}path;
    reinterpret_cast<void(*)(String*,int)>(0xc26e0)(&path,slot);
    std::string filename(path.data,path.length);
    reinterpret_cast<void(*)(String*)>(0x210c8)(&path);
    int ok=0;bool snapshot_ok=true;
    if(action==1){
        if(*reinterpret_cast<int*>(game+0xea4)<=0){result.store(-1);return;}
        std::string backup=filename+".previous";
        bool exists=access(filename.c_str(),F_OK)==0;
        if(exists&&!copy_file(filename.c_str(),backup.c_str())){result.store(-2);return;}
        ok=reinterpret_cast<int(*)(uintptr_t,int)>(0xc8144)(game,slot);
        if(!ok&&exists)rename(backup.c_str(),filename.c_str());
        if(ok){
            FILE *f=fopen(filename.c_str(),"rb");if(f){fsync(fileno(f));fclose(f);}unlink(backup.c_str());
            std::string image=filename.substr(0,filename.find_last_of('.'))+".jpg";
            snapshot_ok=save_snapshot(image);
            if(!snapshot_ok)unlink(image.c_str()); // Never show a previous save's location.
            port_log("Quick snapshot slot=%d saved=%d path=%s",slot,snapshot_ok,image.c_str());
        }
    }else{
        if(access(filename.c_str(),R_OK)!=0){result.store(-3);return;}
        ok=reinterpret_cast<int(*)(uintptr_t,int)>(0xc4cb8)(game,slot);
        if(ok)port_scene_changed();
    }
    port_log("Quick %s slot=%d path=%s result=%d",action==1?"save":"load",slot,filename.c_str(),ok);
    result.store(ok?(snapshot_ok?1:2):-2);
}
