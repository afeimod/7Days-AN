#include "port.h"
#include <android/asset_manager_jni.h>
#include <GLES2/gl2.h>
#include <jni.h>
#include <zlib.h>
#include <map>
#include <tuple>
#include <vector>
#include <string>
#include <cstring>

namespace {
using Key=std::tuple<int,int,GLenum,GLenum,uint32_t>;
struct Replacement {uint32_t w,h;std::vector<unsigned char> compressed;std::string name;};
std::map<Key,Replacement> replacements;
void initialize(){
    static bool loaded=false;if(loaded)return;loaded=true;
    auto *env=static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    if(!env||env->PushLocalFrame(8)<0)return;
    jobject activity=static_cast<jobject>(SDL_GetAndroidActivity());
    jclass cls=env->GetObjectClass(activity);
    jmethodID method=env->GetMethodID(cls,"getAssets","()Landroid/content/res/AssetManager;");
    jobject manager=env->CallObjectMethod(activity,method);
    AAssetManager *assets=AAssetManager_fromJava(env,manager);
    AAsset *file=assets?AAssetManager_open(assets,"texture-overrides.dat",AASSET_MODE_BUFFER):nullptr;
    if(file){
        const auto *p=static_cast<const unsigned char*>(AAsset_getBuffer(file));
        const size_t size=AAsset_getLength(file);
        uint32_t header[2]={};if(p&&size>=8)memcpy(header,p,8);
        size_t offset=8;
        if(header[0]==0x31584448&&header[1]<=512)for(uint32_t i=0;i<header[1];i++){
            uint32_t row[9];if(offset+sizeof row>size)break;
            memcpy(row,p+offset,sizeof row);offset+=sizeof row;
            if(row[5]==0||row[6]==0||row[5]>16384||row[6]>16384||row[8]>255||row[7]>size-offset||row[8]>size-offset-row[7])break;
            Replacement r{row[5],row[6],{},std::string(reinterpret_cast<const char*>(p+offset),row[8])};
            offset+=row[8];r.compressed.assign(p+offset,p+offset+row[7]);offset+=row[7];
            replacements.emplace(Key{row[0],row[1],row[2],row[3],row[4]},std::move(r));
        }
        AAsset_close(file);
    }
    env->PopLocalFrame(nullptr);port_log("Loaded %u redraw signatures",static_cast<unsigned>(replacements.size()));
}
}
bool replacement_image(int w,int h,GLenum format,GLenum type,const void *data,int &ow,int &oh,std::vector<unsigned char>&output){
    if(!data||w<1||h<1)return false;
    if(!((type==GL_UNSIGNED_BYTE&&(format==GL_RGBA||format==GL_RGB))||(type==GL_UNSIGNED_SHORT_5_6_5&&format==GL_RGB)))return false;
    initialize();
    const auto first = replacements.lower_bound(Key{w,h,format,type,0});
    if(first==replacements.end()||first->first>Key{w,h,format,type,UINT32_MAX})return false;
    int bytes=type==GL_UNSIGNED_SHORT_5_6_5?2:format==GL_RGBA?4:3;
    uint32_t checksum=crc32(0,static_cast<const Bytef*>(data),static_cast<size_t>(w)*h*bytes);
    auto found=replacements.find(Key{w,h,format,type,checksum});
    if(found==replacements.end())return false;
    const auto &r=found->second;
    GLint limit;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&limit);if(r.w>limit||r.h>limit)return false;
    uLongf length=static_cast<size_t>(r.w)*r.h*4;output.resize(length);
    if(uncompress(output.data(),&length,r.compressed.data(),r.compressed.size())!=Z_OK||length!=output.size()){output.clear();return false;}
    ow=r.w;oh=r.h;port_log("Redraw applied %s %dx%d -> %dx%d",r.name.c_str(),w,h,ow,oh);return true;
}
