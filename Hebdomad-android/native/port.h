#pragma once
#include <stdint.h>
#include <stddef.h>
#include <SDL3/SDL.h>
void port_log(const char *format, ...);
void *compat_symbol(const char *name);
void *port_dlopen(const char *name, int flags);
void *port_dlsym(void *handle, const char *name);
void port_patch(uintptr_t address, const void *target);
bool install_platform_hooks();
extern "C" void port_gl_viewport(int x,int y,int w,int h);
bool load_engine(const char *path);
void initialize_engine();
int run_engine(int argc, char **argv);
extern SDL_Window *port_window;
