#include "port.h"
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <unistd.h>
#include <stdint.h>
#include <vector>
#include <GLES2/gl2.h>
#include <set>
#include <jni.h>
#include <atomic>
#include <algorithm>
#include <map>
#include <cmath>

SDL_Window *port_window;
static SDL_GLContext port_context;
static std::atomic<int> render_scale{1};
static std::atomic<int> aspect_mode{0};
extern bool filters_antialias_enabled();
static int requested_render_scale(){return filters_antialias_enabled()?2:render_scale.load();}
static int active_render_scale=1;
static int scene_width,scene_height,scene_x,scene_y;
static bool scene_pending_glow;
static GLuint pending_glow_target;
static void finish_direct_scene(){
    if(!scene_pending_glow)return;
    scene_pending_glow=false;
    GLint target;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&target);
    if(static_cast<GLuint>(target)!=pending_glow_target)return;
    int w=0,h=0;if(SDL_GetWindowSizeInPixels(port_window,&w,&h)){
        if(target){
            GLint texture=0;glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,&texture);
            // Read the attachment's dimensions below, at the actual scene viewport.
            GLint viewport[4];glGetIntegerv(GL_VIEWPORT,viewport);w=viewport[2];h=viewport[3];
        }
        extern void soft_glow_scene(GLuint,int,int);soft_glow_scene(target,w,h);
    }
}
static std::atomic<int> actual_width{0},actual_height{0};
extern "C" JNIEXPORT jstring JNICALL Java_org_hebdomad_android_GameActivity_renderInfo(JNIEnv *env,jclass){
    char text[128];snprintf(text,sizeof text,"%d x %d",actual_width.load(),actual_height.load());return env->NewStringUTF(text);
}
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_setAspect(JNIEnv*,jclass,jint value){aspect_mode.store(value);}
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_setRenderScale(JNIEnv*,jclass,jint value){render_scale.store(value==2?2:1);}
extern "C" JNIEXPORT jintArray JNICALL Java_org_hebdomad_android_GameActivity_touchViewport(JNIEnv *env,jclass){
    jint values[4]={scene_x,scene_y,scene_width,scene_height};
    jintArray result=env->NewIntArray(4); if(result)env->SetIntArrayRegion(result,0,4,values); return result;
}
static void scaled_target(int &w,int &h){
    int factor=active_render_scale;if(factor==1||!port_window)return;
    if(w!=scene_width||h!=scene_height)return;
    GLint limit,renderbuffer_limit;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&limit);glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE,&renderbuffer_limit);limit=std::min({limit,renderbuffer_limit,6144});
    float ratio=std::min(static_cast<float>(factor),static_cast<float>(limit)/std::max(w,h));
    // Colour and depth attachments must fit together, including filter targets.
    ratio=std::min(ratio,static_cast<float>(std::sqrt(8388608.0/(static_cast<double>(w)*h))));
    ratio=std::max(1.f,ratio);
    w=static_cast<int>(w*ratio);h=static_cast<int>(h*ratio);
}
extern "C" float port_projection_x=1.8106601f;
extern "C" void port_wide_dimensions(int w,int h){
    if(w<=0||h<=0)return;
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
    if(port_context)return SDL_GL_MakeCurrent(port_window,port_context)?1:0;
    port_context=SDL_GL_CreateContext(port_window);
    bool ok=port_context && SDL_GL_MakeCurrent(port_window,port_context);
    port_log("Android GLES context: %s",ok?"created":SDL_GetError());
    return ok?1:0;
}
static void reset_graphics_metadata();
static void destroy_context(){if(port_context){extern void filters_reset();extern void soft_glow_reset();filters_reset();soft_glow_reset();reset_graphics_metadata();SDL_GL_DestroyContext(port_context);port_context=nullptr;}}
static void refresh_context(){if(port_context)SDL_GL_MakeCurrent(port_window,port_context);}
static std::map<GLuint,std::pair<int,int>> texture_sizes;

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
    if(framebuffer&&x==0&&y==0&&w==scene_width&&h==scene_height){
        // A target can outlive a change to the requested render scale.
        // Draw across its allocated size until it is recreated.
        GLint type=0;
        glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,
                                              GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE,&type);
        if(type==GL_TEXTURE){
            GLint texture=0;
            glGetFramebufferAttachmentParameteriv(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,
                                                  GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,&texture);
            auto size=texture_sizes.find(texture);
            if(size!=texture_sizes.end()){
                w=size->second.first;
                h=size->second.second;
            }
        }
    }
    glViewport(x,y,w,h);
}
static std::set<GLuint> bitmap_textures;
static std::set<GLuint> ui_textures;
static std::set<GLuint> redrawn_textures;
static std::map<GLuint,int> text_scales;
static std::set<GLuint> colour_surfaces;
static void reset_graphics_metadata(){
    texture_sizes.clear();bitmap_textures.clear();ui_textures.clear();
    redrawn_textures.clear();text_scales.clear();colour_surfaces.clear();
    actual_width.store(0);actual_height.store(0);
    scene_pending_glow=false;
}
static void GL_APIENTRY trace_texture(GLenum target,GLint level,GLint internal,GLsizei w,GLsizei h,GLint border,GLenum format,GLenum type,const void *data){
    GLint bound=0;glGetIntegerv(GL_TEXTURE_BINDING_2D,&bound);
    if(target==GL_TEXTURE_2D&&level==0){
        redrawn_textures.erase(bound);
        extern bool replacement_image(int,int,GLenum,GLenum,const void*,int&,int&,std::vector<unsigned char>&);
        std::vector<unsigned char> replacement;int rw=0,rh=0;
        if(replacement_image(w,h,format,type,data,rw,rh,replacement)){
            text_scales.erase(bound);ui_textures.erase(bound);bitmap_textures.erase(bound);colour_surfaces.erase(bound);
            glTexImage2D(target,level,GL_RGBA,rw,rh,border,GL_RGBA,GL_UNSIGNED_BYTE,replacement.data());
            glTexParameteri(target,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(target,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            redrawn_textures.insert(bound);texture_sizes[bound]={rw,rh};return;
        }
    }
    bool capture_surface=(h<=32&&w<=1024)||(w==256&&h>=1024)||
        (w==320&&(h==240||h==211||h<=64))||(w==480&&h==320);
    if(data&&type==GL_UNSIGNED_BYTE&&(format==GL_RGB||format==GL_RGBA)&&capture_surface&&access("capture-text.flag",F_OK)==0){
        static int capture=0;char name[64];snprintf(name,sizeof name,"text-source-%d-%dx%d.tga",capture++,w,h);
        FILE *f=fopen(name,"wb");if(f){unsigned char header[18]={0,0,2};header[12]=w;header[13]=w>>8;header[14]=h;header[15]=h>>8;header[16]=32;header[17]=40;fwrite(header,1,18,f);
            auto *p=static_cast<const unsigned char*>(data);int stride=format==GL_RGBA?4:3;for(int i=0;i<w*h;i++){unsigned char pixel[4]={p[i*stride+2],p[i*stride+1],p[i*stride],stride==4?p[i*stride+3]:static_cast<unsigned char>(255)};fwrite(pixel,1,4,f);}fclose(f);}
    }
    static int n=0;if(n++<60)port_log("Texture image %dx%d format=%x data=%p caller=%p",w,h,format,data,__builtin_return_address(0));
    if(!data)scaled_target(w,h);
    bool text_surface=target==GL_TEXTURE_2D&&level==0&&data&&type==GL_UNSIGNED_BYTE&&
        ((format==GL_RGBA||format==GL_RGB)&&((w==256&&h>=1024)||(h==15&&w<=512)||
         (w==320&&(h==240||h==211||h<=64))||(w==480&&h==320)));
    GLint texture=0;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);text_scales.erase(texture);
    colour_surfaces.erase(texture);
    if(!data)colour_surfaces.insert(texture);
    std::vector<unsigned char> text;
    // Glyph atlas and precomposed single-line text. RGBA alone is insufficient:
    // inventory icons also use RGBA and must retain their original colours.
    if(text_surface){
        GLint limit;glGetIntegerv(GL_MAX_TEXTURE_SIZE,&limit);int dw=0,dh=0;SDL_GetWindowSizeInPixels(port_window,&dw,&dh);
        int desired=(w==256&&h>=1024)?static_cast<int>(std::ceil(dh/240.f)):
            (h==15?static_cast<int>(std::ceil(dh/240.f)):static_cast<int>(std::ceil(dh/static_cast<float>(h))));
        int scale=std::max(1,std::min({desired,limit/std::max(w,h),8}));
        extern bool native_text_image(int,int,int,const void*,std::vector<unsigned char>&);
        std::vector<unsigned char> rgba;const void *input=data;
        if(format==GL_RGB){auto *rgb=static_cast<const unsigned char*>(data);rgba.resize(static_cast<size_t>(w)*h*4);for(int i=0;i<w*h;i++){memcpy(&rgba[i*4],&rgb[i*3],3);rgba[i*4+3]=255;}input=rgba.data();}
        if(scale>1&&native_text_image(w,h,scale,input,text)){text_scales[texture]=scale;colour_surfaces.insert(texture);port_log("Native text %dx%d -> %dx%d",w,h,w*scale,h*scale);w*=scale;h*=scale;data=text.data();format=internal=GL_RGBA;}
    }
    glTexImage2D(target,level,internal,w,h,border,format,type,data);
    if(target==GL_TEXTURE_2D&&level==0)texture_sizes[texture]={w,h};
    // The title/menu is uploaded as a 320x240 RGB surface. It contains
    // already-composited text, so avoid a second bilinear blur when scaled.
    if(text_surface){
        GLint texture;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);ui_textures.insert(texture);
        port_log("High-resolution text surface texture=%d %dx%d scale=%d",texture,w,h,text_scales[texture]);
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
    if(target==GL_TEXTURE_2D&&(name==GL_TEXTURE_MAG_FILTER||name==GL_TEXTURE_MIN_FILTER)){
        GLint texture;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);
        // Interpolate scene texels. Text is reconstructed separately at display
        // resolution; never globally force the scene to nearest sampling.
        if(redrawn_textures.count(texture))value=GL_LINEAR;
        else if(text_scales.count(texture)||ui_textures.count(texture))value=GL_NEAREST;
        else if(colour_surfaces.count(texture))value=GL_LINEAR;
    }
    glTexParameteri(target,name,value);
}
static bool inventory_canvas(){
    // Original ARM engine: main game phase and the pause-menu substate.
    const auto game=reinterpret_cast<uintptr_t(*)()>(0xd9a24)();
    if(!game)return false;
    const int phase=*reinterpret_cast<const int*>(game+0xea0);
    const int menu=*reinterpret_cast<const int*>(game+0xb74);
    return phase==4&&menu>=2&&menu<=10;
}
static void GL_APIENTRY corrected_matrix(GLint location,GLsizei count,GLboolean transpose,const GLfloat *value){
    if(count==1&&value&&value[15]==1.f&&value[11]==0.f&&value[0]>0.f&&value[0]<0.1f&&value[5]<0.f){
        finish_direct_scene();
        // The original 320x240 projection already fills its GL viewport.
        // Keep it for the pause menu so all panels, icons and text share it.
        if(value[0]==2.f/320.f&&value[5]==-2.f/240.f&&
           value[12]==-1.f&&value[13]==1.f&&inventory_canvas()){
            glUniformMatrix4fv(location,count,transpose,value);
            return;
        }
        GLfloat adjusted[16];memcpy(adjusted,value,sizeof adjusted);
        float ratio=(4.f/3)*scene_height/std::max(scene_width,1);
        adjusted[0]*=ratio;adjusted[12]*=ratio;
        glUniformMatrix4fv(location,count,transpose,adjusted);return;
    }
    glUniformMatrix4fv(location,count,transpose,value);
}
static void ui_quad(float *out,float x1,float y1,float x2,float y2,float u1,float v1,float u2,float v2,const float *colour){
    // Preserve square glyphs and icons, extending only screen-wide backdrops
    // and letterbox strips into the extra horizontal field of view.
    if(x1<=8.f&&x1>=0.f&&x2>=312.f&&x2<=320.f&&!inventory_canvas()){
        float extra=std::max(0.f,(240.f*scene_width/std::max(scene_height,1)-320.f)/2.f);
        x1-=extra;x2+=extra;
    }
    const float vertices[6][4]={{x1,y1,u1,v1},{x2,y1,u2,v1},{x2,y2,u2,v2},{x1,y1,u1,v1},{x2,y2,u2,v2},{x1,y2,u1,v2}};
    for(int i=0;i<6;i++){float *v=out+i*9;v[0]=vertices[i][0];v[1]=vertices[i][1];v[2]=0;v[3]=vertices[i][2];v[4]=vertices[i][3];memcpy(v+5,colour,4*sizeof(float));}
}
static void GL_APIENTRY trace_renderbuffer(GLenum target,GLenum internal,GLsizei w,GLsizei h){
    bool scene=w==scene_width&&h==scene_height;
    int original=w;scaled_target(w,h);if(original!=w)port_log("Scene renderbuffer %dx%d (2x rendering)",w,h);
    if(scene){actual_width.store(w);actual_height.store(h);}
    glRenderbufferStorage(target,internal,w,h);
}
static void GL_APIENTRY trace_subtexture(GLenum target,GLint level,GLint x,GLint y,GLsizei w,GLsizei h,GLenum format,GLenum type,const void *data){
    static int n=0;if(n++<12)port_log("Texture update %dx%d caller=%p",w,h,__builtin_return_address(0));
    if(target==GL_TEXTURE_2D&&format==GL_RGBA){
        GLint texture;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);bitmap_textures.insert(texture);
        if(w<=512&&h<=2048)port_log("Bitmap text update texture=%d %dx%d: nearest sampling",texture,w,h);
    }
    GLint texture;glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);
    std::vector<unsigned char> text;
    auto found=text_scales.find(texture);
    if(found!=text_scales.end()&&data&&format==GL_RGBA&&type==GL_UNSIGNED_BYTE){
        extern bool native_text_image(int,int,int,const void*,std::vector<unsigned char>&);
        int scale=found->second;
        if(native_text_image(w,h,scale,data,text)){x*=scale;y*=scale;w*=scale;h*=scale;data=text.data();}
        else return; // Preserve the previous complete image after allocation failure.
    }
    glTexSubImage2D(target,level,x,y,w,h,format,type,data);
}
static void GL_APIENTRY delete_textures(GLsizei count,const GLuint *textures){
    for(int i=0;i<count;i++){
        texture_sizes.erase(textures[i]);
        text_scales.erase(textures[i]);
        bitmap_textures.erase(textures[i]);
        ui_textures.erase(textures[i]);
        colour_surfaces.erase(textures[i]);
        redrawn_textures.erase(textures[i]);
    }
    glDeleteTextures(count,textures);
}
static void GL_APIENTRY shader_source(GLuint shader,GLsizei count,const GLchar *const *parts,const GLint *lengths){
    std::string source;for(int i=0;i<count;i++)source.append(parts[i],lengths&&lengths[i]>=0?lengths[i]:strlen(parts[i]));
    const std::string lookup="TEX2D(uLut, vec2(Lm * (-255.0 / 256.0) + (255.5 / 256.0), TEX2D(uIndex, vUv0).r * uPalScale.x + uPalScale.y)).rgb";
    if(source.find(lookup)!=std::string::npos){
        // Decode each palette index BEFORE interpolation. Interpolating the
        // index or palette rows creates unrelated colours and speckled textures.
        const char *helper=R"GLSL(
uniform HP vec2 uIndexSize;
vec3 paletteColour(HP vec2 uv,float light){
    return TEX2D(uLut,vec2(light*(-255.0/256.0)+(255.5/256.0),TEX2D(uIndex,uv).r*uPalScale.x+uPalScale.y)).rgb;
}
vec3 smoothPalette(HP vec2 uv,float light){
    HP vec2 size=max(uIndexSize,vec2(1.0));
    HP vec2 p=uv*size-0.5;
    HP vec2 f=fract(p),base=(floor(p)+0.5)/size,stepUV=1.0/size;
    return mix(mix(paletteColour(base,light),paletteColour(base+vec2(stepUV.x,0.0),light),f.x),
               mix(paletteColour(base+vec2(0.0,stepUV.y),light),paletteColour(base+stepUV,light),f.x),f.y);
}
)GLSL";
        source.replace(source.find(lookup),lookup.size(),"smoothPalette(vUv0,Lm)");
        source.insert(source.find("void main()"),helper);
    }
    if(source.find("VARY float vLevelU;")!=std::string::npos&&source.find("FRAG_COLOR = vec4(rgb, c.a);")!=std::string::npos){
        source.insert(source.find("void main()"),"uniform float uPortGold;\n");
        const auto pos=source.find("#ifdef FOG",source.find("void main()"));
        if(pos!=std::string::npos)source.insert(pos,"float metal = dot(rgb, vec3(0.299,0.587,0.114));\nrgb = mix(rgb, vec3(metal * 1.35, metal * 0.94, metal * 0.27), uPortGold);\n");
    }
    const char *modified=source.c_str();glShaderSource(shader,1,&modified,nullptr);
}
static void prepare_world_sampling(){
    GLint program;glGetIntegerv(GL_CURRENT_PROGRAM,&program);
    GLint gold=glGetUniformLocation(program,"uPortGold");
    if(gold>=0){extern bool golden_draw(GLuint);glUniform1f(gold,golden_draw(program)?1.f:0.f);}
    GLint light=glGetUniformLocation(program,"uLightmap");if(light<0)return;
    GLint active;glGetIntegerv(GL_ACTIVE_TEXTURE,&active);
    GLint sizeLocation=glGetUniformLocation(program,"uIndexSize");
    if(sizeLocation>=0){
        GLint unit=0,tex=0;glGetUniformiv(program,glGetUniformLocation(program,"uIndex"),&unit);
        glActiveTexture(GL_TEXTURE0+unit);glGetIntegerv(GL_TEXTURE_BINDING_2D,&tex);
        auto found=texture_sizes.find(tex);if(found!=texture_sizes.end())glUniform2f(sizeLocation,found->second.first,found->second.second);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    }
    for(const char *name:{"uTex","uLightmap"}){
        GLint location=glGetUniformLocation(program,name);if(location<0)continue;
        GLint unit=0;glGetUniformiv(program,location,&unit);glActiveTexture(GL_TEXTURE0+unit);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
    }
    glActiveTexture(active);
}
static void GL_APIENTRY trace_draw(GLenum mode,GLsizei count,GLenum type,const void *indices){
    static int n=0;if(n++<40){GLint target,v[4];glGetIntegerv(GL_FRAMEBUFFER_BINDING,&target);glGetIntegerv(GL_VIEWPORT,v);port_log("Draw count=%d framebuffer=%d viewport=%dx%d caller=%p",count,target,v[2],v[3],__builtin_return_address(0));}
    if(glIsEnabled(GL_DEPTH_TEST)){GLint target;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&target);scene_pending_glow=true;pending_glow_target=target;}
    prepare_world_sampling();glDrawElements(mode,count,type,indices);
}
static void GL_APIENTRY scene_draw_arrays(GLenum mode,GLint first,GLsizei count){
    if(glIsEnabled(GL_DEPTH_TEST)){GLint target;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&target);scene_pending_glow=true;pending_glow_target=target;}
    glDrawArrays(mode,first,count);
}
static void GL_APIENTRY scene_bind(GLenum type,GLuint next){
    GLint current;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&current);
    if(type==GL_FRAMEBUFFER&&next!=static_cast<GLuint>(current)&&scene_pending_glow)finish_direct_scene();
    glBindFramebuffer(type,next);
}
static void *gl_proc(const char *name){
    if(!strcmp(name,"glBindFramebuffer"))return reinterpret_cast<void*>(scene_bind);
    if(!strcmp(name,"glViewport"))return reinterpret_cast<void*>(port_gl_viewport);
    if(!strcmp(name,"glTexImage2D"))return reinterpret_cast<void*>(trace_texture);
    if(!strcmp(name,"glTexParameteri"))return reinterpret_cast<void*>(crisp_sampling);
    if(!strcmp(name,"glUniformMatrix4fv"))return reinterpret_cast<void*>(corrected_matrix);
    if(!strcmp(name,"glRenderbufferStorage"))return reinterpret_cast<void*>(trace_renderbuffer);
    if(!strcmp(name,"glTexSubImage2D"))return reinterpret_cast<void*>(trace_subtexture);
    if(!strcmp(name,"glDeleteTextures"))return reinterpret_cast<void*>(delete_textures);
    if(!strcmp(name,"glShaderSource"))return reinterpret_cast<void*>(shader_source);
    if(!strcmp(name,"glDrawElements"))return reinterpret_cast<void*>(trace_draw);
    if(!strcmp(name,"glDrawArrays"))return reinterpret_cast<void*>(scene_draw_arrays);
    return reinterpret_cast<void*>(SDL_GL_GetProcAddress(name));
}
static bool repair_target;
void port_scene_changed(){repair_target=true;}
static void maintain_scene_target(){
    static int applied_aspect=-1,drawable_w=0,drawable_h=0;
    int w=0,h=0;
    if(!SDL_GetWindowSizeInPixels(port_window,&w,&h)||w<2||h<2)return;
    auto *target=reinterpret_cast<int*>(0x3dec4c);
    GLint previous=0;glGetIntegerv(GL_FRAMEBUFFER_BINDING,&previous);
    bool valid=target[0]&&glIsFramebuffer(target[0])&&target[1]&&glIsTexture(target[1]);
    if(valid){glBindFramebuffer(GL_FRAMEBUFFER,target[0]);valid=glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE;}
    glBindFramebuffer(GL_FRAMEBUFFER,previous);
    int next_scale=requested_render_scale();
    bool changed=active_render_scale!=next_scale||applied_aspect!=aspect_mode.load()||drawable_w!=w||drawable_h!=h;
    if(!changed&&!repair_target&&valid)return;
    // A load or resource-tier flush can delete attachments without clearing
    // the engine's cached framebuffer size. Rebuild incomplete targets too.
    if(!valid||changed){
        active_render_scale=next_scale;
        port_wide_dimensions(w,h);
        GLint active,texture,renderbuffer;
        glGetIntegerv(GL_ACTIVE_TEXTURE,&active);glGetIntegerv(GL_TEXTURE_BINDING_2D,&texture);
        glGetIntegerv(GL_RENDERBUFFER_BINDING,&renderbuffer);
        const GLuint old=target[0],oldtex=target[1],olddepth=target[2];
        if(!valid){
            if(old&&glIsFramebuffer(old))glDeleteFramebuffers(1,&old);
            if(oldtex&&glIsTexture(oldtex))delete_textures(1,&oldtex);
            if(olddepth&&glIsRenderbuffer(olddepth))glDeleteRenderbuffers(1,&olddepth);
            target[0]=target[1]=target[2]=0;
        }
        target[3]=target[4]=0;
        reinterpret_cast<void(*)(int*,int,int,int)>(0x102ad4)(target,scene_width,scene_height,1);
        glBindFramebuffer(GL_FRAMEBUFFER,previous==static_cast<GLint>(old)?0:previous);
        glActiveTexture(active);glBindTexture(GL_TEXTURE_2D,texture==static_cast<GLint>(oldtex)?0:texture);
        glBindRenderbuffer(GL_RENDERBUFFER,renderbuffer==static_cast<GLint>(olddepth)?0:renderbuffer);
        port_log("Scene target rebuilt %dx%d scale=%d after transition=%d",scene_width,scene_height,active_render_scale,repair_target);
    }
    applied_aspect=aspect_mode.load();drawable_w=w;drawable_h=h;repair_target=false;
}
static void swap(){
    // Loaders can pump their own frames. Never enter the same save/load or
    // target rebuild while its outer frame is still running.
    static bool presenting=false;
    if(presenting){SDL_GL_SwapWindow(port_window);return;}
    presenting=true;
    struct Finish{bool &flag;~Finish(){flag=false;}}finish{presenting};
    finish_direct_scene();extern void filters_present();filters_present();
    extern void quick_state_frame();quick_state_frame();
    SDL_GL_SwapWindow(port_window);extern void touch_frame_finished();touch_frame_finished();
    extern void gameplay_frame();gameplay_frame();
    maintain_scene_target();
}
static void swap_interval(int interval){SDL_GL_SetSwapInterval(interval);}
static void no_dynapi_init(){}
static void native_resolution(unsigned char){
    *reinterpret_cast<unsigned char*>(0x3dec28)=0;
    port_log("Original low-resolution render mode disabled; using native drawable dimensions");
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
    port_patch(0x100c94,reinterpret_cast<void*>(ui_quad));
    port_patch(0x106464,reinterpret_cast<void*>(port_wide_projection));
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
