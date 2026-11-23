#include "port.h"
#include <GLES2/gl2.h>
#include <jni.h>
#include <atomic>
#include <algorithm>
#include <vector>
#include <cstdio>
#include <unistd.h>

static std::atomic<int> strength{0};
static GLuint program, fbo, vbo, original, ping, pong;
static int width, height, bw, bh;
extern "C" JNIEXPORT void JNICALL Java_org_hebdomad_android_GameActivity_setLighting(JNIEnv*,jclass,jint value){strength.store(std::clamp(static_cast<int>(value),0,100));}

static const char *vertex=R"(
attribute vec2 Position; attribute vec2 TexCoord; varying highp vec2 uv;
void main(){gl_Position=vec4(Position,0.0,1.0);uv=TexCoord;})";
static const char *fragment=R"(
precision highp float;
uniform sampler2D Source; uniform sampler2D Glow;
uniform vec2 Step; uniform int Pass; uniform float Strength; varying highp vec2 uv;
void main(){
 vec4 c=texture2D(Source,uv);
 if(Pass==0){
  vec3 bright=max(c.rgb-vec3(0.28),vec3(0.0));
  float peak=max(c.r,max(c.g,c.b));
  // Keep a low-frequency contribution from the whole frame so the effect
  // remains visible in dim scenes; highlights still produce stronger halos.
  gl_FragColor=vec4(c.rgb*0.45+bright*smoothstep(0.28,0.75,peak),1.0);
 }else if(Pass==1){
  vec3 b=c.rgb*0.227027;
  b+=(texture2D(Source,uv+Step*1.384615).rgb+texture2D(Source,uv-Step*1.384615).rgb)*0.316216;
  b+=(texture2D(Source,uv+Step*3.230769).rgb+texture2D(Source,uv-Step*3.230769).rgb)*0.070270;
  gl_FragColor=vec4(b,1.0);
 }else{
  vec3 glow=texture2D(Glow,uv).rgb;
  // Screen composition spreads highlights without flattening dark geometry.
  vec3 soft=1.0-(1.0-c.rgb)*(1.0-clamp(glow*1.8,0.0,1.0));
  gl_FragColor=vec4(mix(c.rgb,soft,Strength),c.a);
 }
})";

static GLuint compile(GLenum type,const char *source){
 GLuint s=glCreateShader(type);glShaderSource(s,1,&source,nullptr);glCompileShader(s);
 GLint ok;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
 if(!ok){char log[1024];glGetShaderInfoLog(s,sizeof log,nullptr,log);port_log("Soft glow shader: %s",log);glDeleteShader(s);return 0;}return s;
}
void soft_glow_reset(){
 if(program)glDeleteProgram(program);if(fbo)glDeleteFramebuffers(1,&fbo);if(vbo)glDeleteBuffers(1,&vbo);
 GLuint textures[]={original,ping,pong};glDeleteTextures(3,textures);
 program=fbo=vbo=original=ping=pong=0;width=height=bw=bh=0;
}
static void allocate(GLuint texture,int w,int h){
 glBindTexture(GL_TEXTURE_2D,texture);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
}
static bool output(GLuint texture){
 glBindFramebuffer(GL_FRAMEBUFFER,fbo);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
 return glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE;
}
static void draw(GLuint source,int pass,int w,int h,float dx=0,float dy=0){
 glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,source);glViewport(0,0,w,h);
 glUniform1i(glGetUniformLocation(program,"Pass"),pass);glUniform2f(glGetUniformLocation(program,"Step"),dx,dy);
 glDrawArrays(GL_TRIANGLE_STRIP,0,4);
}
static void evidence(const char *name,int w,int h){
 std::vector<unsigned char> pixels(static_cast<size_t>(w)*h*4);glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());
 FILE *file=fopen(name,"wb");if(!file)return;fprintf(file,"P6\n%d %d\n255\n",w,h);
 for(int y=h-1;y>=0;y--)for(int x=0;x<w;x++)fwrite(&pixels[(static_cast<size_t>(y)*w+x)*4],1,3,file);fclose(file);
}

// Called when the engine finishes its 3D framebuffer, before UI composition.
// Capture avoids reading a texture while writing to its framebuffer attachment.
void soft_glow_scene(GLuint scene,int w,int h){
 int value=strength.load();if(value==0||w<2||h<2)return;
 GLint oldprogram,oldfbo,oldbuffer,active,tex0,tex1,viewport[4];GLboolean mask[4];
 glGetIntegerv(GL_CURRENT_PROGRAM,&oldprogram);glGetIntegerv(GL_FRAMEBUFFER_BINDING,&oldfbo);
 glGetIntegerv(GL_ARRAY_BUFFER_BINDING,&oldbuffer);glGetIntegerv(GL_ACTIVE_TEXTURE,&active);
 glActiveTexture(GL_TEXTURE1);glGetIntegerv(GL_TEXTURE_BINDING_2D,&tex1);glActiveTexture(GL_TEXTURE0);glGetIntegerv(GL_TEXTURE_BINDING_2D,&tex0);
 glGetIntegerv(GL_VIEWPORT,viewport);glGetBooleanv(GL_COLOR_WRITEMASK,mask);
 GLenum caps[]={GL_BLEND,GL_DEPTH_TEST,GL_STENCIL_TEST,GL_SCISSOR_TEST,GL_CULL_FACE};GLboolean enabled[5];
 for(int i=0;i<5;i++){enabled[i]=glIsEnabled(caps[i]);glDisable(caps[i]);}glColorMask(1,1,1,1);
 struct Attr{GLint enabled,size,type,norm,stride,buffer;void *pointer;}attr[2];
 for(int i=0;i<2;i++){auto &a=attr[i];glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_ENABLED,&a.enabled);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_SIZE,&a.size);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_TYPE,&a.type);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_NORMALIZED,&a.norm);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_STRIDE,&a.stride);glGetVertexAttribiv(i,GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING,&a.buffer);glGetVertexAttribPointerv(i,GL_VERTEX_ATTRIB_ARRAY_POINTER,&a.pointer);}
 if(!program){
  GLuint vs=compile(GL_VERTEX_SHADER,vertex),fs=compile(GL_FRAGMENT_SHADER,fragment);
  if(vs&&fs){program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glBindAttribLocation(program,0,"Position");glBindAttribLocation(program,1,"TexCoord");glLinkProgram(program);GLint ok;glGetProgramiv(program,GL_LINK_STATUS,&ok);if(!ok){glDeleteProgram(program);program=0;}}
  if(vs)glDeleteShader(vs);if(fs)glDeleteShader(fs);
 }
 if(program){
  if(!fbo){glGenFramebuffers(1,&fbo);glGenBuffers(1,&vbo);const GLfloat quad[]={-1,-1,0,0,1,-1,1,0,-1,1,0,1,1,1,1,1};glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,sizeof quad,quad,GL_STATIC_DRAW);glGenTextures(1,&original);glGenTextures(1,&ping);glGenTextures(1,&pong);}
  if(width!=w||height!=h){width=w;height=h;float ratio=std::min(0.25f,512.f/std::max(w,h));bw=std::max(2,int(w*ratio));bh=std::max(2,int(h*ratio));allocate(original,w,h);allocate(ping,bw,bh);allocate(pong,bw,bh);port_log("Soft glow scene=%dx%d blur=%dx%d",w,h,bw,bh);}
  glUseProgram(program);glUniform1i(glGetUniformLocation(program,"Source"),0);glUniform1i(glGetUniformLocation(program,"Glow"),1);glUniform1f(glGetUniformLocation(program,"Strength"),value/100.f);
  glBindBuffer(GL_ARRAY_BUFFER,vbo);glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);glVertexAttribPointer(0,2,GL_FLOAT,0,16,(void*)0);glVertexAttribPointer(1,2,GL_FLOAT,0,16,(void*)8);
  glBindFramebuffer(GL_FRAMEBUFFER,scene);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,original);
  bool compare=access("soft-glow-compare.flag",F_OK)==0;
  if(compare)evidence("soft-glow-before.ppm",w,h);
  glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,w,h);
  bool ok=glGetError()==GL_NO_ERROR&&output(ping);if(ok)draw(original,0,bw,bh);
  for(int iteration=0;iteration<3&&ok;iteration++){ok=output(pong);if(ok)draw(ping,1,bw,bh,(1.f+iteration)/bw,0);if(ok)ok=output(ping);if(ok)draw(pong,1,bw,bh,0,(1.f+iteration)/bh);}
  if(ok)ok=glGetError()==GL_NO_ERROR;
  if(ok){glBindFramebuffer(GL_FRAMEBUFFER,scene);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,ping);draw(original,2,w,h);}
  if(compare){glBindFramebuffer(GL_FRAMEBUFFER,scene);evidence("soft-glow-after.ppm",w,h);unlink("soft-glow-compare.flag");port_log("Same-frame soft glow evidence: target=%u strength=%d complete=%d error=%x",scene,value,ok,glGetError());}
 }
 for(int i=0;i<2;i++){auto &a=attr[i];glBindBuffer(GL_ARRAY_BUFFER,a.buffer);glVertexAttribPointer(i,a.size,a.type,a.norm,a.stride,a.pointer);if(a.enabled)glEnableVertexAttribArray(i);else glDisableVertexAttribArray(i);}
 glBindBuffer(GL_ARRAY_BUFFER,oldbuffer);glUseProgram(oldprogram);glBindFramebuffer(GL_FRAMEBUFFER,oldfbo);
 glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,tex1);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,tex0);glActiveTexture(active);
 glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);glColorMask(mask[0],mask[1],mask[2],mask[3]);for(int i=0;i<5;i++)if(enabled[i])glEnable(caps[i]);
}
