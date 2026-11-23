// Explicit ABI adapters for the GLIBC-linked ARM executable.
// Unsupported interfaces fail with diagnostics rather than silently succeeding.
#include "port.h"
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <locale.h>
#include <langinfo.h>
#include <ctype.h>
#include <wctype.h>
#include <wchar.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdint.h>
#include <dirent.h>
#include <time.h>
#include <math.h>

extern char **environ;
extern "C" int port_dlclose(void *handle);
static FILE *compat_stdin=stdin,*compat_stderr=stderr;
static thread_local unsigned char game_tls[4096];
extern "C" void *game_tls_get_addr(const uint32_t *key) {
    if(key[1]>=sizeof(game_tls)){port_log("Unsupported TLS offset %u",key[1]);abort();}
    return game_tls+key[1];
}
extern "C" void *game_find_exidx(uintptr_t pc,int *count) {
    if(pc>=0x10000 && pc<0x46d000){*count=3287;return reinterpret_cast<void*>(0x3c3b1c);}
    using F=void*(*)(uintptr_t,int*);static auto real=reinterpret_cast<F>(dlsym(RTLD_DEFAULT,"__gnu_Unwind_Find_exidx"));
    if(real)return real(pc,count);*count=0;return nullptr;
}
extern "C" int game_atexit(void(*callback)(void*),void *object,void*) {
    // Keep callbacks inside the lifetime of the mapped executable. Android owns process shutdown.
    struct Exit {void(*callback)(void*);void *object;};
    static Exit callbacks[512];static unsigned count;
    if(count>=512)return -1;callbacks[count++]={callback,object};return 0;
}
extern "C" int game_xstat(int,const char *path,struct stat64 *out){return stat64(path,out);}
extern "C" int game_lxstat(int,const char *path,struct stat64 *out){return lstat64(path,out);}
extern "C" int game_fxstat(int,int fd,struct stat64 *out){return fstat64(fd,out);}
extern "C" size_t game_mb_cur_max(){return MB_CUR_MAX;}
extern "C" long game_wcstol(const wchar_t *s,wchar_t **end,int base,int){return wcstol(s,end,base);}
extern "C" const char *game_dgettext(const char*,const char *text){return text;}
extern "C" const char *game_bindtextdomain(const char*,const char *path){return path?path:".";}
extern "C" const char *game_codeset(const char*,const char *encoding){return encoding?encoding:"UTF-8";}

struct GlibcLocale {
    void *locales[13];
    const unsigned short *ctype_b;
    const int *ctype_tolower,*ctype_toupper;
    const char *names[13];
};
static GlibcLocale glibc_locale={};
static unsigned short ctype_flags[384];
static int ctype_lower[384],ctype_upper[384];
static pthread_once_t locale_once=PTHREAD_ONCE_INIT;
static thread_local void *current_locale;
static void initialize_locale() {
    for(int i=-128;i<256;++i){int c=static_cast<unsigned char>(i);unsigned f=0;
        if(isupper(c))f|=0x0100;if(islower(c))f|=0x0200;if(isalpha(c))f|=0x0400;
        if(isdigit(c))f|=0x0800;if(isxdigit(c))f|=0x1000;if(isspace(c))f|=0x2000;
        if(isprint(c))f|=0x4000;if(isgraph(c))f|=0x8000;if(isblank(c))f|=1;
        if(iscntrl(c))f|=2;if(ispunct(c))f|=4;if(isalnum(c))f|=8;
        ctype_flags[i+128]=f;ctype_lower[i+128]=tolower(c);ctype_upper[i+128]=toupper(c);
    }
    ctype_lower[127]=ctype_upper[127]=-1;ctype_flags[127]=0;
    glibc_locale.ctype_b=ctype_flags+128;glibc_locale.ctype_tolower=ctype_lower+128;glibc_locale.ctype_toupper=ctype_upper+128;
    for(unsigned i=0;i<13;++i)glibc_locale.names[i]="C";
}
extern "C" void *game_newlocale(int,const char *name,void*) {
    pthread_once(&locale_once,initialize_locale);
    if(name && strcmp(name,"C") && strcmp(name,"POSIX") && strcmp(name,"C.UTF-8") && *name) {
        port_log("Unsupported game locale: %s",name);errno=ENOENT;return nullptr;
    }
    return &glibc_locale;
}
extern "C" void *game_duplocale(void*){return game_newlocale(0,"C",nullptr);}
extern "C" void game_freelocale(void*){}
extern "C" void *game_uselocale(void *loc){void *old=current_locale?current_locale:&glibc_locale;if(loc)current_locale=loc;return old;}
extern "C" const char *game_langinfo(int item,void*) {
    // GNU nl_langinfo encodes category in high 16 bits. C numeric and monetary facets.
    unsigned category=static_cast<unsigned>(item)>>16,index=static_cast<unsigned>(item)&0xffff;
    static const wchar_t point=L'.',empty=L'\0';
    if(category==1){if(index==0)return ".";if(index==1 || index==2)return "";
        if(index==3)return reinterpret_cast<const char*>(&point);
        if(index==4)return reinterpret_cast<const char*>(&empty);
    }
    if(category==4){static const char none[]={127,0};if(index>=7 && index<=20)return none;return "";}
    if(category==0 && index==14)return "ANSI_X3.4-1968";
    using Fn=char*(*)(int);static auto real=reinterpret_cast<Fn>(dlsym(RTLD_DEFAULT,"nl_langinfo"));
    return real?real(item):"";
}
extern "C" size_t game_strxfrm(char *dest,const char *src,size_t n,void*){return strxfrm(dest,src,n);}
extern "C" int game_strcoll(const char *a,const char *b,void*){return strcoll(a,b);}
extern "C" size_t game_wcsxfrm(wchar_t *dest,const wchar_t *src,size_t n,void*){return wcsxfrm(dest,src,n);}
extern "C" int game_wcscoll(const wchar_t *a,const wchar_t *b,void*){return wcscoll(a,b);}
extern "C" size_t game_strftime(char *dest,size_t n,const char *fmt,const struct tm *t,void*){return strftime(dest,n,fmt,t);}
extern "C" size_t game_wcsftime(wchar_t *dest,size_t n,const wchar_t *fmt,const struct tm *t,void*){return wcsftime(dest,n,fmt,t);}
extern "C" float game_strtof(const char*s,char**end,void*){return strtof(s,end);}
extern "C" double game_strtod(const char*s,char**end,void*){return strtod(s,end);}
extern "C" long double game_strtold(const char*s,char**end,void*){return strtold(s,end);}
extern "C" wint_t game_towlower(wint_t x,void*){return towlower(x);}
extern "C" wint_t game_towupper(wint_t x,void*){return towupper(x);}
extern "C" wctype_t game_wctype(const char *x,void*){return wctype(x);}
extern "C" int game_iswctype(wint_t x,wctype_t t,void*){return iswctype(x,t);}
extern "C" int game_isnan(double x){return isnan(x);}
extern "C" int game_isnanf(float x){return isnan(x);}
extern "C" int game_isinf(double x){return isinf(x);}
extern "C" int game_isinff(float x){return isinf(x);}

static void unsupported() {
    port_log("Unsupported GLIBC function called from %p",__builtin_return_address(0));abort();
}
static const char *aliases[][2]={
    {"__pthread_key_create","pthread_key_create"},{"__errno_location","__errno"},
    {"__strtok_r","strtok_r"},{"open64","open"},{"openat64","openat"},
    {"fopen64","fopen"},{"readdir64","readdir64"},{nullptr,nullptr}
};
#define MAP(n,f) if(!strcmp(name,n))return reinterpret_cast<void*>(f)
void *compat_symbol(const char *name) {
    if(!strcmp(name,"glViewport"))return reinterpret_cast<void*>(port_gl_viewport);
    if(!strncmp(name,"_ITM_",5)||!strncmp(name,"_ZGTt",5)||!strcmp(name,"__gmon_start__"))return nullptr;
    MAP("environ",&environ);MAP("_environ",&environ);MAP("__environ",&environ);
    MAP("stdin",&compat_stdin);MAP("stderr",&compat_stderr);
    MAP("__tls_get_addr",game_tls_get_addr);MAP("__gnu_Unwind_Find_exidx",game_find_exidx);
    MAP("__cxa_atexit",game_atexit);
    MAP("__xstat64",game_xstat);MAP("__lxstat64",game_lxstat);MAP("__fxstat64",game_fxstat);
    MAP("__ctype_get_mb_cur_max",game_mb_cur_max);MAP("__wcstol_internal",game_wcstol);
    MAP("dgettext",game_dgettext);MAP("bindtextdomain",game_bindtextdomain);MAP("bind_textdomain_codeset",game_codeset);
    MAP("__newlocale",game_newlocale);MAP("__duplocale",game_duplocale);MAP("__freelocale",game_freelocale);MAP("__uselocale",game_uselocale);
    MAP("__nl_langinfo_l",game_langinfo);MAP("__strxfrm_l",game_strxfrm);MAP("__strcoll_l",game_strcoll);
    MAP("__wcsxfrm_l",game_wcsxfrm);MAP("__wcscoll_l",game_wcscoll);
    MAP("__strftime_l",game_strftime);MAP("__wcsftime_l",game_wcsftime);
    MAP("__strtof_l",game_strtof);MAP("__strtod_l",game_strtod);MAP("strtold_l",game_strtold);
    MAP("__towlower_l",game_towlower);MAP("__towupper_l",game_towupper);MAP("__wctype_l",game_wctype);MAP("__iswctype_l",game_iswctype);
    MAP("__isnan",game_isnan);MAP("__isnanf",game_isnanf);MAP("__isinf",game_isinf);MAP("__isinff",game_isinff);
    MAP("dlopen",port_dlopen);MAP("dlsym",port_dlsym);MAP("dlclose",port_dlclose);
    for(auto &a:aliases)if(a[0]&&!strcmp(name,a[0]))return dlsym(RTLD_DEFAULT,a[1]);
    void *p=dlsym(RTLD_DEFAULT,name);
    if(!p){port_log("Unimplemented interface (will fail if called): %s",name);return reinterpret_cast<void*>(unsupported);}
    return p;
}
