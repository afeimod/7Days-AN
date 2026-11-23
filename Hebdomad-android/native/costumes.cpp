#include "port.h"
#include <jni.h>
#include <atomic>
#include <array>
#include <vector>
#include <cstring>

static std::atomic<int> selected{0};
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_setCostume(JNIEnv*,jclass,jint value){if(value>=0&&value<8)selected.store(value);}
struct GameString {const char *data;unsigned length;char local[16];explicit GameString(const char *name):data(name),length(strlen(name)){} };
static uintptr_t model(const char *name){GameString s(name);return reinterpret_cast<uintptr_t(*)(GameString*)>(0x1e074)(&s);}
static uintptr_t texture(const char *name){GameString s(name);return reinterpret_cast<uintptr_t(*)(GameString*)>(0xe61d8)(&s);}
// Private views borrow cache-owned geometry. They are never passed to cache
// destructors, and are refreshed before use after every game frame/load.
static std::array<uint32_t,31> view;
static std::vector<unsigned char> materials;
static bool applied;
void costume_frame(uintptr_t player){
    if(*reinterpret_cast<unsigned char*>(player+0xc)){applied=false;return;}
    int choice=selected.load();
    if(choice==0&&!applied)return;
    const char *names[]={"dj_ken_a","dj_ken_ab","dj_ken_ac","dj_ken_b","dj_ken_bb","dj_ken_bc","dj_evilken_a","dj_evilken_b"};
    uintptr_t original=model("r_ken_a");
    if(!original)return;
    if(choice==0){*reinterpret_cast<uintptr_t*>(player+0x12c)=original;applied=false;return;}
    uintptr_t outfit=texture(names[choice]);if(!outfit)return;
    uintptr_t base_outfit=texture("dj_ken_a");if(!base_outfit)return;
    memcpy(view.data(),reinterpret_cast<void*>(original),view.size()*4);
    auto *source=reinterpret_cast<uint32_t*>(original);
    size_t material_bytes=source[7]-source[6];
    if(material_bytes==0||material_bytes>4096||material_bytes%0x3c)return;
    materials.resize(material_bytes);memcpy(materials.data(),reinterpret_cast<void*>(source[6]),material_bytes);
    for(size_t i=0;i<material_bytes;i+=0x3c){
        auto *material=materials.data()+i;
        uintptr_t current=*reinterpret_cast<uintptr_t*>(material+0x38);
        if(current==base_outfit)*reinterpret_cast<uintptr_t*>(material+0x38)=outfit;
    }
    view[6]=reinterpret_cast<uintptr_t>(materials.data());view[7]=view[8]=view[6]+material_bytes;
    *reinterpret_cast<uintptr_t*>(player+0x12c)=reinterpret_cast<uintptr_t>(view.data());
    static int last=-1;if(last!=choice){port_log("Costume %d applied: %s",choice,names[choice]);last=choice;}applied=true;
}
