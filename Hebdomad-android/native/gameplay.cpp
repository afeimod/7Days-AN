#include "port.h"
#include <jni.h>
#include <atomic>
#include <algorithm>
#include <GLES2/gl2.h>
#include <cstring>

// The bundled, hash-pinned engine has four weapon model branches in
// Player::SetWeapon (0xbe524): knife, rapier, pistol, Desert Eagle.
// Requests and the novice option are applied only by the render/game thread.
static std::atomic<bool> beginner{false};
static std::atomic<int> weapon{-1};
static std::atomic<bool> golden{false};
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_setBeginner(JNIEnv*,jclass,jboolean enabled){beginner.store(enabled);}
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_equipWeapon(JNIEnv*,jclass,jint value){if(value>=0&&value<=5){golden.store(value==5);weapon.store(value==5?4:value);}}
bool golden_draw(GLuint program){
    if(!golden.load())return false;
    const auto game=reinterpret_cast<uintptr_t(*)()>(0xd9a24)();if(!game)return false;
    const auto player=game+0x6d0;
    if(*reinterpret_cast<unsigned char*>(player+0xc)||*reinterpret_cast<int*>(player+0x3c)!=4)return false;
    const auto model=*reinterpret_cast<uintptr_t*>(player+0x130);if(!model)return false;
    auto *materials=reinterpret_cast<uintptr_t*>(model+0x18);
    if(!materials[0]||materials[1]<=materials[0])return false;
    const auto texture=*reinterpret_cast<uintptr_t*>(materials[0]+0x38);if(!texture)return false;
    GLint loc=glGetUniformLocation(program,"uIndex");if(loc<0)return false;
    GLint unit=0,active=0,bound=0;glGetUniformiv(program,loc,&unit);glGetIntegerv(GL_ACTIVE_TEXTURE,&active);
    glActiveTexture(GL_TEXTURE0+unit);glGetIntegerv(GL_TEXTURE_BINDING_2D,&bound);glActiveTexture(active);
    return static_cast<GLuint>(bound)==*reinterpret_cast<GLuint*>(texture+0x10);
}
void gameplay_frame(){
    const auto game=reinterpret_cast<uintptr_t(*)()>(0xd9a24)();
    if(!game)return;
    const int phase=*reinterpret_cast<int*>(game+0xea0);
    if(phase!=4)return;
    const auto player=game+0x6d0;
    // Same invulnerability flag used by the engine's own "god" command.
    *reinterpret_cast<unsigned char*>(player+0x38)=beginner.load()?1:0;
    extern void costume_frame(uintptr_t);costume_frame(player);
    int selection=weapon.load();
    if(selection>=0&&!*reinterpret_cast<unsigned char*>(player+0xc)){
        if(!weapon.compare_exchange_strong(selection,-1))return;
        reinterpret_cast<void(*)(uintptr_t,int)>(0xbe524)(player,selection);
        port_log("Unlocked weapon equipped: %d",selection);
    }
}
