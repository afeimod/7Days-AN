#include "port.h"
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <stdint.h>
#include <vector>
#include <GLES2/gl2.h>
#include <set>
#include <jni.h>
#include <atomic>
#include <algorithm>

SDL_Window *port_window;
static SDL_GLContext port_context;
static std::atomic<int> render_scale{1};
static std::atomic<int> aspect_mode{0};
static int scene_width,scene_height,scene_x,scene_y;
static std::atomic<int> actual_width{0},actual_height{0};
extern "C" JNIEXPORT jstring JNICALL Java_org_hebdomad_android_GameActivity_renderInfo(JNIEnv *env,jclass){
    char text[128];snprintf(text,sizeof text,"%d x %d",actual_width.load(),actual_height.load());return env->NewStringUTF(text);
}
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_setAspect(JNIEnv*,jclass,jint value){aspect_mode.store(value);}
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_setRenderScale(JNIEnv*,jclass,jint value){render_scale.store(value==2?2:1);}
static void scaled_target(int &w,int &h){
    if(!port_window)return;
    bool legacy_scene_target=w==320&&h==240;
    if(legacy_scene_target){
        int target_width=scene_width,target_height=scene_height;
        if((target_width<=0||target_height<=0)&&!SDL_GetWindowSizeInPixels(port_window,&target_width,&target_height))return;
        if(target_width<=0||target_height<=0)return;
        w=target_width;h=target_height;
    }else if(w!=scene_width||h!=scene_height)return;
    int factor=render_scale.load();if(factor==1)return;
    GLint limit,renderbuffer_limit;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&limit);glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE,&renderbuffer_limit);limit=std::min({limit,renderbuffer_limit,6144});
    float ratio=std::min(static_cast<float>(factor),static_cast<float>(limit)/std::max(w,h));
    w=static_cast<int>(w*ratio);h=static_cast<int>(h*ratio);
}
extern "C" float port_projection_x=1.8106601f;
extern "C" void port_wide_dimensions(int w,int h){
    float ratio=aspect_mode.load()==1?16.f/9:aspect_mode.load()==2?4.f/3:aspect_mode.load()==3?5.f/3:static_cast<float>(w)/h;
    scene_width=w;scene_height=h;
    if(w/static_cast<float>(h)>ratio)scene_width=static_cast<int>(h*ratio);else scene_height=static_cast<int>(w/ratio);
    scene_x=(w-scene_width)/2;scene_y=(h-scene_height)/2;
    auto *size=reinterpret_cast<int*>(0x3dec2c);size[0]=scene_width;size[1]=scene_height;
    auto *viewport=reinterpret_cast<int*>(0x3dec34);viewport[0]=scene_x;viewport[1]=scene_y;viewport[2]=scene_width;viewport[3]=scene_height;
    port_projection_x=2.4142137f*static_cast<float>(scene_height)/scene_width;
    static int lastw=0,lasth=0;
    if(lastw!=scene_width||lasth!=scene_height){port_log("Native widescreen scene %dx%d projection-x=%f",scene_width,scene_height,port_projection_x);lastw=scene_width;lasth=scene_height;}
}
extern "C" __attribute__((naked)) void port_wide_resize(){
    __asm__ volatile("push {r0-r3,r12,lr}\nmov r1,r3\nbl port_wide_dimensions\npop {r0-r3,r12,lr}\nmov r1,#0\nldr pc,=0x105ff0\n.ltorg\n");
}
extern "C" uint32_t projection_bits(){uint32_t bits;memcpy(&bits,&port_projection_x,4);return bits;}
extern "C" __attribute__((naked)) void port_wide_projection(){
    __asm__ volatile("push {r0-r2,r4,r12,lr}\nbl projection_bits\nmov r3,r0\npop {r0-r2,r4,r12,lr}\nldr pc,=0x10646c\n.ltorg\n");
}
static void *pulse_handle=reinterpret_cast<void*>(0x5a170001);
static bool set_hint(const char *name,const char *value){
    // N900 game deliberately selects SDL's silent driver because it uses
    // PulseAudio separately. The Android Pulse shim itself uses SDL playback.
    if(!strcmp(name,SDL_HINT_AUDIO_DRIVER)&&value&&!strcmp(value,"dummy"))value="aaudio,openslES";
    return SDL_SetHint(name,value);
}
static bool set_hint_priority(const char *name,const char *value,SDL_HintPriority priority){
    if(!strcmp(name,SDL_HINT_AUDIO_DRIVER)&&value&&!strcmp(value,"dummy"))value="aaudio,openslES";
    return SDL_SetHintWithPriority(name,value,priority);
}

struct PulseSpec {int format;uint32_t rate;uint8_t channels;};
struct PulseStream {SDL_AudioStream *stream;int rate,channels;size_t bytes;bool logged;int peak;double energy;FILE *capture;};
static void *pulse_new(const char*,const char*,int,const char*,const char*,const PulseSpec *spec,const void*,const void*,int *error) {
    port_log("Audio requested format=%d rate=%u channels=%u",spec->format,spec->rate,spec->channels);
    if(spec->format!=3 || !spec->channels || !spec->rate){if(error)*error=1;return nullptr;}
    if(!(SDL_WasInit(SDL_INIT_AUDIO)&SDL_INIT_AUDIO) && !SDL_InitSubSystem(SDL_INIT_AUDIO)) {
        port_log("Audio subsystem failed: %s",SDL_GetError());if(error)*error=1;return nullptr;
    }
    SDL_AudioSpec target={SDL_AUDIO_S16LE,spec->channels,static_cast<int>(spec->rate)};
    SDL_AudioStream *s=SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK,&target,nullptr,nullptr);
    if(!s){port_log("SDL audio open failed: %s",SDL_GetError());if(error)*error=1;return nullptr;}
    if(!SDL_ResumeAudioStreamDevice(s)){port_log("Audio resume failed: %s",SDL_GetError());SDL_DestroyAudioStream(s);if(error)*error=1;return nullptr;}
    SDL_AudioSpec actual{};int frames=0;
    SDL_GetAudioDeviceFormat(SDL_GetAudioStreamDevice(s),&actual,&frames);
    port_log("Audio driver=%s output=%d Hz / %d channels / format=%x frames=%d gain=%f",SDL_GetCurrentAudioDriver(),actual.freq,actual.channels,actual.format,frames,SDL_GetAudioDeviceGain(SDL_GetAudioStreamDevice(s)));
    FILE *capture=access("capture-audio.flag",F_OK)==0?fopen("audio-s16le.pcm","wb"):nullptr;
    if(error)*error=0;
    return new PulseStream{s,static_cast<int>(spec->rate),spec->channels,0,false,0,0,capture};
}
static int pulse_write(PulseStream *s,const void *data,size_t length,int *error) {
    const int16_t *pcm=static_cast<const int16_t*>(data);
    for(size_t i=0;i<length/2;i++){int v=pcm[i];if(abs(v)>s->peak)s->peak=abs(v);s->energy+=static_cast<double>(v)*v;}
    if(s->capture && s->bytes<static_cast<size_t>(s->rate*s->channels*2*15)){fwrite(data,1,length,s->capture);fflush(s->capture);}
    if(!SDL_PutAudioStreamData(s->stream,data,static_cast<int>(length))){if(error)*error=1;return -1;}
    // pa_simple_write is blocking. Bound latency and prevent the original mixer thread from spinning.
    int threshold=s->rate*s->channels*2/8;
    while(SDL_GetAudioStreamQueued(s->stream)>threshold)SDL_Delay(3);
    s->bytes+=length;
    if(s->bytes%static_cast<size_t>(s->rate*s->channels*4)<length){
        port_log("Audio PCM bytes=%u peak=%d mean-square=%.1f queued=%d paused=%d",static_cast<unsigned>(s->bytes),s->peak,s->energy/(s->bytes/2),SDL_GetAudioStreamQueued(s->stream),SDL_AudioStreamDevicePaused(s->stream));s->logged=true;
    }
    return 0;
}
static void pulse_free(PulseStream *s){if(s){if(s->capture)fclose(s->capture);SDL_DestroyAudioStream(s->stream);delete s;}}
void *port_dlopen(const char *name,int flags) {
    if(name && !strcmp(name,"libpulse-simple.so.0"))return pulse_handle;
    // The Android renderer replaces X11 and EGL setup. Other runtime libraries use Android's loader.
    return dlopen(name,flags);
}
void *port_dlsym(void *handle,const char *name) {
    if(handle==pulse_handle){
        if(!strcmp(name,"pa_simple_new"))return reinterpret_cast<void*>(pulse_new);
        if(!strcmp(name,"pa_simple_write"))return reinterpret_cast<void*>(pulse_write);
        if(!strcmp(name,"pa_simple_free"))return reinterpret_cast<void*>(pulse_free);
        return nullptr;
    }
    return dlsym(handle,name);
}
extern "C" int port_dlclose(void *handle){return handle==pulse_handle?0:dlclose(handle);}

static SDL_Window *create_window(const char *title,int width,int height,SDL_WindowFlags flags) {
    port_log("Create Android window: %s, %dx%d, flags=%llx",title,width,height,static_cast<unsigned long long>(flags));
    flags|=SDL_WINDOW_OPENGL|SDL_WINDOW_FULLSCREEN;
    port_window=SDL_CreateWindow(title,width,height,flags);
    if(!port_window)port_log("Window failed: %s",SDL_GetError());
    return port_window;
}
static int create_context() {
    port_context=SDL_GL_CreateContext(port_window);
    bool ok=port_context && SDL_GL_MakeCurrent(port_window,port_context);
    port_log("Android GLES context: %s",ok?"created":SDL_GetError());
    return ok?1:0;
}
static void destroy_context(){if(port_context){extern void filters_reset();filters_reset();SDL_GL_DestroyContext(port_context);port_context=nullptr;}}
static void refresh_context(){if(port_context)SDL_GL_MakeCurrent(port_window,port_context);}
extern "C" void SDLCALL port_gl_viewport(GLint x,GLint y,GLsizei w,GLsizei h){
    static int count=0;
    if(count++<12){GLint target=0;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&target);port_log("Game GL viewport %d,%d %dx%d framebuffer=%d",x,y,w,h,target);}
    if(port_window && x==0 && y==0 && w==800 && h==480){
        int pw=0,ph=0;
        if(SDL_GetWindowSizeInPixels(port_window,&pw,&ph) && pw>0 && ph>0){
            glViewport(0,0,pw,ph);
            static int logged_w=0,logged_h=0;
            if(logged_w!=pw||logged_h!=ph){port_log("Scaled fixed 800x480 game viewport to drawable %dx%d",pw,ph);logged_w=pw;logged_h=ph;}
            return;
        }
    }
    GLint framebuffer;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&framebuffer);
    if(framebuffer)scaled_target(w,h);
    glViewport(x,y,w,h);
}
static std::set<GLuint> bitmap_textures;
static std::set<GLuint> ui_textures;
static void GL_APIENTRY trace_texture(GLenum target,GLint level,GLint internal,GLsizei w,GLsizei h,GLint border,GLenum format,GLenum type,const void *data){
    int original_w=w,original_h=h;
    if(!data)scaled_target(w,h);
    if(original_w!=w||original_h!=h)port_log("Scaled scene texture %dx%d -> %dx%d",original_w,original_h,w,h);
    static int n=0;if(n++<60)port_log("Texture image %dx%d format=%x data=%p caller=%p",w,h,format,data,__builtin_return_address(0));
    glTexImage2D(target,level,internal,w,h,border,format,type,data);
    // The title/menu is uploaded as a 320x240 RGB surface. It contains
    // already-composited text, so avoid a second bilinear blur when scaled.
    if(target==GL_TEXTURE_2D && ((w==320&&h==240)||(w==320&&h<=64)||(w==480&&h==320))){
        GLint texture;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);ui_textures.insert(texture);
        port_log("UI surface texture=%d %dx%d: nearest sampling",texture,w,h);
    }
    // Text and Chinese glyph uploads use RGBA (0x1908). Keep those bitmap
    // atlases nearest sampled at every scale; linear filtering is what made
    // the small original glyphs appear grey and out of focus.
    if(format==GL_RGBA){
        GLint texture;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);bitmap_textures.insert(texture);
        if(w<=512&&h<=2048)port_log("Bitmap text texture=%d %dx%d: nearest sampling",texture,w,h);
    }
}
static void GL_APIENTRY crisp_sampling(GLenum target,GLenum name,GLint value){
    if(target==GL_TEXTURE_2D&&name==GL_TEXTURE_MAG_FILTER)value=GL_NEAREST;
    if(target==GL_TEXTURE_2D&&(name==GL_TEXTURE_MAG_FILTER||name==GL_TEXTURE_MIN_FILTER)){
        GLint texture;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);
        // UI textures contain a complete composited frame. Keep their original
        // filtering so enlarged menu text does not become blocky.
        if(bitmap_textures.count(texture))value=GL_NEAREST;
    }
    glTexParameteri(target,name,value);
}
static void GL_APIENTRY corrected_matrix(GLint location,GLsizei count,GLboolean transpose,const GLfloat *value){
    if(count==1&&value&&value[15]==1.f&&value[11]==0.f&&value[0]>0.f&&value[0]<0.1f&&value[5]<0.f){
        GLfloat adjusted[16];memcpy(adjusted,value,sizeof adjusted);
        float ratio=(4.f/3)*scene_height/std::max(scene_width,1);
        adjusted[0]*=ratio;adjusted[12]*=ratio;
        glUniformMatrix4fv(location,count,transpose,adjusted);return;
    }
    glUniformMatrix4fv(location,count,transpose,value);
}
static void GL_APIENTRY trace_renderbuffer(GLenum target,GLenum internal,GLsizei w,GLsizei h){
    bool scene=(w==scene_width&&h==scene_height)||(w==320&&h==240);
    int original_w=w,original_h=h;scaled_target(w,h);
    if(scene){actual_width.store(w);actual_height.store(h);}
    if(original_w!=w||original_h!=h)port_log("Scaled scene renderbuffer %dx%d -> %dx%d",original_w,original_h,w,h);
    glRenderbufferStorage(target,internal,w,h);
}
static void GL_APIENTRY trace_subtexture(GLenum target,GLint level,GLint x,GLint y,GLsizei w,GLsizei h,GLenum format,GLenum type,const void *data){
    static int n=0;if(n++<12)port_log("Texture update %dx%d caller=%p",w,h,__builtin_return_address(0));
    if(target==GL_TEXTURE_2D&&format==GL_RGBA){
        GLint texture;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);bitmap_textures.insert(texture);
        if(w<=512&&h<=2048)port_log("Bitmap text update texture=%d %dx%d: nearest sampling",texture,w,h);
    }
    glTexSubImage2D(target,level,x,y,w,h,format,type,data);
}
static void GL_APIENTRY trace_draw(GLenum mode,GLsizei count,GLenum type,const void *indices){
    static int n=0;if(n++<40){GLint target,v[4];glGetIntegerv(GL_FRAMEBUFFER_BINDING,&target);glGetIntegerv(GL_VIEWPORT,v);port_log("Draw count=%d framebuffer=%d viewport=%dx%d caller=%p",count,target,v[2],v[3],__builtin_return_address(0));}
    glDrawElements(mode,count,type,indices);
}
static void *gl_proc(const char *name){
    if(!strcmp(name,"glViewport"))return reinterpret_cast<void*>(port_gl_viewport);
    if(!strcmp(name,"glTexImage2D"))return reinterpret_cast<void*>(trace_texture);
    if(!strcmp(name,"glTexParameteri"))return reinterpret_cast<void*>(crisp_sampling);
    if(!strcmp(name,"glUniformMatrix4fv"))return reinterpret_cast<void*>(corrected_matrix);
    if(!strcmp(name,"glRenderbufferStorage"))return reinterpret_cast<void*>(trace_renderbuffer);
    if(!strcmp(name,"glTexSubImage2D"))return reinterpret_cast<void*>(trace_subtexture);
    if(!strcmp(name,"glDrawElements"))return reinterpret_cast<void*>(trace_draw);
    return reinterpret_cast<void*>(SDL_GL_GetProcAddress(name));
}
static void swap(){
    extern void filters_present();filters_present();SDL_GL_SwapWindow(port_window);extern void touch_frame_finished();touch_frame_finished();
    static int applied_scale=0,applied_aspect=-1;
    if(applied_scale!=render_scale.load()||applied_aspect!=aspect_mode.load()){
        int w,h;SDL_GetWindowSizeInPixels(port_window,&w,&h);port_wide_dimensions(w,h);
        auto *target=reinterpret_cast<int*>(0x3dec4c);target[3]=target[4]=0;
        reinterpret_cast<void(*)(int*,int,int,int)>(0x102ad4)(target,scene_width,scene_height,1);
        applied_scale=render_scale.load();applied_aspect=aspect_mode.load();
        port_log("Applied rendering %dx%d scale=%d aspect=%d GLES=%x",scene_width,scene_height,applied_scale,applied_aspect,glGetError());
    }
}
static void swap_interval(int interval){SDL_GL_SetSwapInterval(interval);}
static void no_dynapi_init(){}
static void native_resolution(unsigned char){
    *reinterpret_cast<unsigned char*>(0x3dec28)=0;
    port_log("Original low-resolution render mode disabled; using native drawable dimensions");
}
static void transition_overlay(int state){
    auto *game_state=reinterpret_cast<const unsigned char*>(state);
    int global_state=reinterpret_cast<int(*)()>(0xd9a24)();
    if(*reinterpret_cast<const int*>(global_state+0x6e0)==2)return;
    if(!game_state[0xa8])return;
    float alpha=*reinterpret_cast<const float*>(game_state+0xc4);
    if(!(alpha>0.f))return;

    float color[4];
    memcpy(color,game_state+0xac,3*sizeof(float));
    color[3]=alpha;
    // 过门遮罩按实际场景宽高比扩展，覆盖 4:3 投影留下的两侧区域。
    float width=scene_height>0?240.f*scene_width/scene_height:320.f;
    float x=(320.f-width)*0.5f;
    reinterpret_cast<void(*)(float,float,float,float,const float*)>(0x106748)(x,0.f,width,240.f,color);
}
static void dialogue_rectangle(float x,float y,float width,float height,const void *color){
    uintptr_t caller=reinterpret_cast<uintptr_t>(__builtin_return_address(0))&~uintptr_t(1);
    if(caller>=0xf3ebc&&caller<0xf3fac&&x==0.f&&width==320.f&&height==32.f&&(y==0.f||y==208.f)){
        float projection_scale=(4.f/3)*scene_height/std::max(scene_width,1);
        if(projection_scale>0.f){
            width=320.f/projection_scale;
            x=(320.f-width)*0.5f;
        }
    }
    alignas(8) unsigned char rectangle[220];
    reinterpret_cast<void(*)(void*,float,float,float,float,float,float,float,float,const void*)>(0x100c94)(
        rectangle,x,y,x+width,y+height,0.f,0.f,1.f,1.f,color);
    reinterpret_cast<void(*)(void*,int)>(0x1035dc)(rectangle,0);
}

// SDL guarantees this append-only ABI order. The original 3.4.18 executable has
// exactly 1,270 entries, matching the 3.4.20 header included with this project.
static const char *api_names[]={
#define SDL_DYNAPI_PROC(ret,name,params,args,result) #name,
#include "../third_party/SDL_dynapi_procs.h"
#undef SDL_DYNAPI_PROC
};
static void unsupported_sdl(){port_log("Unavailable SDL API invoked from %p",__builtin_return_address(0));abort();}
bool install_platform_hooks() {
    extern void install_bitmap_font();install_bitmap_font();
    static_assert(sizeof(api_names)/sizeof(*api_names)==1270,"SDL dispatch layout changed");
    auto **table=reinterpret_cast<void**>(0x3d6b50);
    for(size_t i=0;i<1270;++i) {
        if(reinterpret_cast<uintptr_t>(table[i])<0x120000 || reinterpret_cast<uintptr_t>(table[i])>=0x135000){port_log("Unexpected SDL dispatch entry %u",static_cast<unsigned>(i));return false;}
    }
    void *sdl=dlopen("libSDL3.so",RTLD_NOW|RTLD_GLOBAL);
    if(!sdl){port_log("Could not open native SDL: %s",dlerror());return false;}
    for(size_t i=0;i<1270;++i){
        void *fn=dlsym(sdl,api_names[i]);
        extern const bool *touch_keyboard(int*);
        table[i]=!strcmp(api_names[i],"SDL_CreateWindow")?reinterpret_cast<void*>(create_window):
            !strcmp(api_names[i],"SDL_SetHint")?reinterpret_cast<void*>(set_hint):
            !strcmp(api_names[i],"SDL_SetHintWithPriority")?reinterpret_cast<void*>(set_hint_priority):
            !strcmp(api_names[i],"SDL_GetKeyboardState")?reinterpret_cast<void*>(touch_keyboard):
            (fn?fn:reinterpret_cast<void*>(unsupported_sdl));
    }
    port_patch(0x11fff0,reinterpret_cast<void*>(no_dynapi_init));
    port_patch(0x105dbc,reinterpret_cast<void*>(native_resolution));
    port_patch(0x105edc,reinterpret_cast<void*>(port_wide_resize));
    port_patch(0x106464,reinterpret_cast<void*>(port_wide_projection));
    port_patch(0x0f5a00,reinterpret_cast<void*>(transition_overlay));
    port_patch(0x106748,reinterpret_cast<void*>(dialogue_rectangle));
    port_patch(0xe3cf0,reinterpret_cast<void*>(create_context));
    port_patch(0xe46c8,reinterpret_cast<void*>(destroy_context));
    port_patch(0xe4760,reinterpret_cast<void*>(refresh_context));
    port_patch(0xe47bc,reinterpret_cast<void*>(gl_proc));
    port_patch(0xe4828,reinterpret_cast<void*>(swap));
    port_patch(0xe484c,reinterpret_cast<void*>(swap_interval));
    port_log("SDL dispatch and Android graphics hooks installed");
    return true;
}

extern "C" __attribute__((visibility("default"))) int SDL_main(int argc,char **argv) {
    SDL_SetHint(SDL_HINT_AUDIO_DRIVER,"aaudio,openslES");
    SDL_SetHint(SDL_HINT_AUDIO_DEVICE_STREAM_ROLE,"Game");
    const char *directory=argc>1?argv[1]:getenv("HEBDOMAD_DATA");
    if(!directory || chdir(directory)!=0){SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR,"Hebdomad","Cannot open private game directory.",nullptr);return 1;}
    freopen("engine-output.txt","w",stdout);freopen("engine-errors.txt","w",stderr);
    setvbuf(stdout,nullptr,_IOLBF,0);setvbuf(stderr,nullptr,_IONBF,0);
    if(!load_engine("engine.arm")){
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR,"Hebdomad","The ARM engine could not initialize. See port-log.txt.",nullptr);return 1;
    }
    initialize_engine();
    char arg0[]="hebdomad",arg1[]="--data",arg2[]=".",arg3[]="--spk",arg4[]="res.spk";
    char *args[]={arg0,arg1,arg2,arg3,arg4,nullptr};
    return run_engine(5,args);
}
