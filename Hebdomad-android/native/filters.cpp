#include "port.h"
#include <GLES2/gl2.h>
#include <jni.h>
#include <atomic>
#include <algorithm>
#include <vector>
#include <stdio.h>
#include <unistd.h>
#include <zlib.h>
#include "filter_shaders.h"

static std::atomic<int> mode{0};
static GLuint programs[6],capture,filter_input,upscale,lut,fbo,vbo;
static int width,height,input_width,input_height,upwidth,upheight,last_mode=-1;
static const char *simple=R"GLSL(
#ifdef VERTEX
attribute vec4 VertexCoord;attribute vec2 TexCoord;varying highp vec2 uv;
void main(){gl_Position=VertexCoord;uv=TexCoord;}
#endif
#ifdef FRAGMENT
precision highp float;
uniform sampler2D Texture;uniform sampler2D Original;uniform int Mode;varying highp vec2 uv;
uniform vec2 TextureSize;
float luma(vec3 rgb){return dot(rgb,vec3(0.2126,0.7152,0.0722));}
void main(){
 vec4 c=texture2D(Texture,uv);
 vec2 pixel=gl_FragCoord.xy;
 if(Mode==3){
  vec2 cell=fract(pixel/4.0)-0.5;
  c.rgb*=mix(0.68,1.0,1.0-smoothstep(0.23,0.62,length(cell)));
 }
 if(Mode==4)c.rgb*=mod(floor(pixel.y),3.0)<1.0?0.60:1.0;
 if(Mode==5){
  vec2 px=1.0/TextureSize;
  float nw=luma(texture2D(Texture,uv+px*vec2(-1.0,1.0)).rgb);
  float ne=luma(texture2D(Texture,uv+px).rgb);
  float sw=luma(texture2D(Texture,uv-px).rgb);
  float se=luma(texture2D(Texture,uv+px*vec2(1.0,-1.0)).rgb);
  float center=luma(c.rgb);
  float low=min(center,min(min(nw,ne),min(sw,se)));
  float high=max(center,max(max(nw,ne),max(sw,se)));
  if(high-low>max(0.035,high*0.105)){
   vec2 dir=vec2(sw+se-nw-ne,nw+sw-ne-se);
   float reduction=max((nw+ne+sw+se)*0.03125,0.0078125);
   dir=clamp(dir/(min(abs(dir.x),abs(dir.y))+reduction),-6.0,6.0)*px;
   vec3 a=0.5*(texture2D(Texture,uv-dir/6.0).rgb+texture2D(Texture,uv+dir/6.0).rgb);
   vec3 b=a*0.5+0.25*(texture2D(Texture,uv-dir*0.5).rgb+texture2D(Texture,uv+dir*0.5).rgb);
   c.rgb=(luma(b)<low||luma(b)>high)?a:b;
  }
 }
 gl_FragColor=vec4(c.rgb,1.0);
}
#endif
)GLSL";

extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_setFilter(JNIEnv*,jclass,jint m){
 mode.store(std::clamp(static_cast<int>(m),0,5));
}
bool filters_antialias_enabled(){return mode.load()==5;}
static GLuint shader(GLenum type,const char *text){
 GLuint s=glCreateShader(type);
 const char *prefix=type==GL_VERTEX_SHADER?"#define VERTEX\n":"#define FRAGMENT\n";
 const char *parts[]={prefix,text};glShaderSource(s,2,parts,nullptr);glCompileShader(s);
 GLint ok;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
 if(!ok){char log[2048];glGetShaderInfoLog(s,sizeof log,nullptr,log);port_log("Filter shader failed: %s",log);glDeleteShader(s);return 0;}
 return s;
}
static GLuint program(const char *text){
 GLuint v=shader(GL_VERTEX_SHADER,text),f=shader(GL_FRAGMENT_SHADER,text);
 if(!v||!f){if(v)glDeleteShader(v);if(f)glDeleteShader(f);return 0;}
 GLuint p=glCreateProgram();glAttachShader(p,v);glAttachShader(p,f);
 glBindAttribLocation(p,0,"VertexCoord");glBindAttribLocation(p,1,"TexCoord");glLinkProgram(p);
 glDeleteShader(v);glDeleteShader(f);GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);
 if(!ok){char log[2048];glGetProgramInfoLog(p,sizeof log,nullptr,log);port_log("Filter link failed: %s",log);glDeleteProgram(p);return 0;}
 return p;
}
static void texture(GLuint t,int w,int h,GLenum sampling){
 glBindTexture(GL_TEXTURE_2D,t);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,sampling);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,sampling);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
}
static void draw(GLuint p,GLuint t,int iw,int ih,int ow,int oh,int m){
 glUseProgram(p);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,t);glViewport(0,0,ow,oh);
 glUniform1i(glGetUniformLocation(p,"Texture"),0);glUniform1i(glGetUniformLocation(p,"decal"),0);
 glUniform1i(glGetUniformLocation(p,"LUT"),1);glUniform1i(glGetUniformLocation(p,"Mode"),m);
 glUniform2f(glGetUniformLocation(p,"TextureSize"),iw,ih);glUniform2f(glGetUniformLocation(p,"InputSize"),iw,ih);
 glUniform2f(glGetUniformLocation(p,"OutputSize"),ow,oh);
 const GLfloat identity[]={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
 glUniformMatrix4fv(glGetUniformLocation(p,"MVPMatrix"),1,GL_FALSE,identity);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
}
static void reset_scale_targets(){
 if(filter_input)glDeleteTextures(1,&filter_input);
 if(upscale)glDeleteTextures(1,&upscale);
 filter_input=upscale=0;
 input_width=input_height=upwidth=upheight=0;
}
void filters_reset(){
 for(GLuint &p:programs){if(p)glDeleteProgram(p);p=0;}
 reset_scale_targets();
 if(capture)glDeleteTextures(1,&capture);
 if(lut)glDeleteTextures(1,&lut);
 if(fbo)glDeleteFramebuffers(1,&fbo);
 if(vbo)glDeleteBuffers(1,&vbo);
 capture=lut=fbo=vbo=0;
 width=height=0;
 last_mode=-1;
}
static bool attach(GLuint target){
 glBindFramebuffer(GL_FRAMEBUFFER,fbo);
 glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target,0);
 return glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE;
}
static bool prepare_lut(){
 if(lut)return true;
 std::vector<unsigned char> pixels(256*256*4);
 uLongf size=pixels.size();
 if(uncompress(pixels.data(),&size,hq4x_lut,sizeof hq4x_lut)!=Z_OK||size!=pixels.size())return false;
 glGenTextures(1,&lut);
 texture(lut,256,256,GL_NEAREST);
 glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());
 return true;
}
static bool render(int m,int w,int h){
 if(m!=1&&m!=2){
  glBindFramebuffer(GL_FRAMEBUFFER,0);
  draw(programs[0],capture,w,h,w,h,m);
  return true;
 }
 if(!programs[m])programs[m]=program(m==1?xbr_lv2:hq4x);
 if(!programs[m]||(m==2&&!prepare_lut()))return false;
 if(!upscale){
  GLint limit=0;
  glGetIntegerv(GL_MAX_TEXTURE_SIZE,&limit);
  // Bound the intermediate to 3840 pixels on the long side. Every input
  // pixel still has a full 4x4 neighbourhood in the scaler output.
  double ratio=std::min(1.0,std::min(960,limit/4)/static_cast<double>(std::max(w,h)));
  input_width=std::max(1,static_cast<int>(w*ratio+0.5));
  input_height=std::max(1,static_cast<int>(h*ratio+0.5));
  upwidth=input_width*4;
  upheight=input_height*4;
  glGenTextures(1,&filter_input);
  texture(filter_input,input_width,input_height,GL_NEAREST);
  glGenTextures(1,&upscale);
  texture(upscale,upwidth,upheight,GL_LINEAR);
 }
 if(!attach(filter_input))return false;
 glBindTexture(GL_TEXTURE_2D,capture);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
 draw(programs[0],capture,w,h,input_width,input_height,0);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
 if(!attach(upscale))return false;
 if(m==2){
  glActiveTexture(GL_TEXTURE1);
  glBindTexture(GL_TEXTURE_2D,lut);
 }
 draw(programs[m],filter_input,input_width,input_height,upwidth,upheight,m);
 glBindFramebuffer(GL_FRAMEBUFFER,0);
 draw(programs[0],upscale,upwidth,upheight,w,h,0);
 return true;
}
static void save_frame(int m,int w,int h){
 std::vector<unsigned char> pixels(static_cast<size_t>(w)*h*4);
 glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());
 char name[64];snprintf(name,sizeof name,"filter-compare-%d.ppm",m);
 FILE *file=fopen(name,"wb");if(!file)return;
 fprintf(file,"P6\n%d %d\n255\n",w,h);
 for(int y=h-1;y>=0;--y)for(int x=0;x<w;++x)fwrite(&pixels[(static_cast<size_t>(y)*w+x)*4],1,3,file);
 fclose(file);
}
void filters_present(){
 int m=mode.load();
 // Optional developer evidence: all five outputs are computed from the same
 // untouched game frame, so animation cannot masquerade as a filter effect.
 bool compare=access("filter-compare.flag",F_OK)==0;
 if(m==0&&!compare)return;
 int w,h;if(!SDL_GetWindowSizeInPixels(port_window,&w,&h)||w<2||h<2)return;
 GLint oldprogram,oldfbo,oldbuffer,active,tex0,tex1,viewport[4];GLboolean mask[4];
 glGetIntegerv(GL_CURRENT_PROGRAM,&oldprogram);glGetIntegerv(GL_FRAMEBUFFER_BINDING,&oldfbo);
 glGetIntegerv(GL_ARRAY_BUFFER_BINDING,&oldbuffer);glGetIntegerv(GL_ACTIVE_TEXTURE,&active);
 glActiveTexture(GL_TEXTURE1);glGetIntegerv(GL_TEXTURE_BINDING_2D,&tex1);
 glActiveTexture(GL_TEXTURE0);glGetIntegerv(GL_TEXTURE_BINDING_2D,&tex0);
 glGetIntegerv(GL_VIEWPORT,viewport);glGetBooleanv(GL_COLOR_WRITEMASK,mask);
 GLenum caps[]={GL_BLEND,GL_DEPTH_TEST,GL_STENCIL_TEST,GL_SCISSOR_TEST,GL_CULL_FACE};GLboolean enabled[5];
 for(int i=0;i<5;i++){enabled[i]=glIsEnabled(caps[i]);glDisable(caps[i]);}glColorMask(1,1,1,1);
 struct Attr{GLint enabled,size,type,norm,stride,buffer;void *pointer;}attr[2];
 for(int i=0;i<2;i++){auto &a=attr[i];glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_ENABLED,&a.enabled);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_SIZE,&a.size);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_TYPE,&a.type);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,&a.norm);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_STRIDE,&a.stride);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING,&a.buffer);glGetVertexAttribPointerv(i,GL_VERTEX_ATTRIB_ARRAY_POINTER,&a.pointer);}
 if(!programs[0])programs[0]=program(simple);
 if(programs[0]){
  if(!capture){
   glGenTextures(1,&capture);glGenFramebuffers(1,&fbo);glGenBuffers(1,&vbo);
   const GLfloat quad[]={-1,-1,0,0,1,-1,1,0,-1,1,0,1,1,1,1,1};
   glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,sizeof quad,quad,GL_STATIC_DRAW);
  }
  if(width!=w||height!=h){
   reset_scale_targets();
   texture(capture,w,h,GL_NEAREST);width=w;height=h;
  }
  glBindFramebuffer(GL_FRAMEBUFFER,0);glBindTexture(GL_TEXTURE_2D,capture);
  glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,w,h);
  glBindBuffer(GL_ARRAY_BUFFER,vbo);glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);
  glVertexAttribPointer(0,2,GL_FLOAT,0,16,(void*)0);glVertexAttribPointer(1,2,GL_FLOAT,0,16,(void*)8);
  if(compare){
   for(int test=0;test<6;++test)if(render(test,w,h))save_frame(test,w,h);
   unlink("filter-compare.flag");port_log("Same-frame filter evidence saved: %dx%d",w,h);
  }
  if(!render(m,w,h)){glBindFramebuffer(GL_FRAMEBUFFER,0);draw(programs[0],capture,w,h,w,h,0);port_log("Filter %d unavailable",m);mode.store(0);}
  GLenum error=glGetError();
  if(last_mode!=m){port_log("Filter %d drawable=%dx%d input=%dx%d intermediate=%dx%d output=%dx%d GLES error=%x; game viewport=%d,%d %dx%d",m,w,h,(m==1||m==2)?input_width:w,(m==1||m==2)?input_height:h,upwidth,upheight,w,h,error,viewport[0],viewport[1],viewport[2],viewport[3]);last_mode=m;}
 }
 for(int i=0;i<2;i++){auto &a=attr[i];glBindBuffer(GL_ARRAY_BUFFER,a.buffer);glVertexAttribPointer(i,a.size,a.type,a.norm,a.stride,a.pointer);if(a.enabled)glEnableVertexAttribArray(i);else glDisableVertexAttribArray(i);}
 glBindBuffer(GL_ARRAY_BUFFER,oldbuffer);glUseProgram(oldprogram);glBindFramebuffer(GL_FRAMEBUFFER,oldfbo);
 glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,tex1);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,tex0);
 glActiveTexture(active);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glColorMask(mask[0],mask[1],mask[2],mask[3]);
 for(int i=0;i<5;i++)if(enabled[i])glEnable(caps[i]);
}
