#include "port.h"
#include <jni.h>
#include <mutex>
#include <string.h>
static std::mutex input_mutex;
static bool physical[SDL_SCANCODE_COUNT],sampled[SDL_SCANCODE_COUNT];
static unsigned pending[SDL_SCANCODE_COUNT];
static bool snapshot_ready;
static bool merged[SDL_SCANCODE_COUNT];
static bool controller[SDL_SCANCODE_COUNT];
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_controllerKey(JNIEnv*,jclass,jint scan,jboolean down){
    if(scan<=0||scan>=SDL_SCANCODE_COUNT)return;
    std::lock_guard<std::mutex> lock(input_mutex);
    if(down&&!controller[scan]&&pending[scan]<8)++pending[scan];
    controller[scan]=down;
}
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_touchKey(JNIEnv*,jclass,jint scan,jboolean down){
    if(scan<=0 || scan>=SDL_SCANCODE_COUNT)return;
    std::lock_guard<std::mutex> lock(input_mutex);
    if(down && !physical[scan]){if(pending[scan]<8)++pending[scan];}
    physical[scan]=down;
}
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_clearTouch(JNIEnv*,jclass){
    std::lock_guard<std::mutex> lock(input_mutex);
    memset(physical,0,sizeof physical);memset(sampled,0,sizeof sampled);memset(pending,0,sizeof pending);snapshot_ready=false;
}
// One consistent snapshot for every action query in a rendered game frame.
// Short taps survive until the game polls them, even at very low frame rates.
const bool *touch_keyboard(int *count){
    int n=0;const bool *real=SDL_GetKeyboardState(&n);
    std::lock_guard<std::mutex> lock(input_mutex);
    if(!snapshot_ready){
        for(int i=0;i<SDL_SCANCODE_COUNT;i++){
            bool previous=sampled[i];
            sampled[i]=physical[i] || controller[i] || (pending[i] && !previous);
            if(sampled[i] && pending[i])--pending[i];
        }
        snapshot_ready=true;
    }
    for(int i=0;i<SDL_SCANCODE_COUNT;i++)merged[i]=(i<n&&real[i])||sampled[i];
    if(count)*count=SDL_SCANCODE_COUNT;return merged;
}
void touch_frame_finished(){std::lock_guard<std::mutex> lock(input_mutex);snapshot_ready=false;}
