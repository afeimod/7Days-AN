// Reverse engineered Android platform adapter for the original ARM executable.
// This file does not reconstruct the original game source or emulate a CPU.
#include "port.h"
#include <elf.h>
#include <sys/mman.h>
#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <vector>
#include <signal.h>
#include <ucontext.h>
#include <android/log.h>
#include <stdarg.h>
#include <stdlib.h>
#include <errno.h>
#include <algorithm>

static std::vector<unsigned char> image;
static FILE *log_file;
void port_log(const char *format, ...) {
    char text[4096]; va_list args; va_start(args,format);
    vsnprintf(text,sizeof(text),format,args); va_end(args);
    __android_log_write(ANDROID_LOG_INFO,"Hebdomad",text);
    if(log_file){fprintf(log_file,"%s\n",text);fflush(log_file);}
}
static void crash(int sig, siginfo_t *info, void *context) {
    auto *uc=static_cast<ucontext_t*>(context);
    port_log("FAULT signal=%d address=%p PC=%08lx LR=%08lx SP=%08lx R0=%08lx R1=%08lx R2=%08lx R3=%08lx",
        sig,info->si_addr,uc->uc_mcontext.arm_pc,uc->uc_mcontext.arm_lr,
        uc->uc_mcontext.arm_sp,uc->uc_mcontext.arm_r0,uc->uc_mcontext.arm_r1,
        uc->uc_mcontext.arm_r2,uc->uc_mcontext.arm_r3);
    signal(sig,SIG_DFL); raise(sig);
}
void port_patch(uintptr_t address,const void *target) {
    uint32_t *p=reinterpret_cast<uint32_t*>(address);
    p[0]=0xe51ff004; // ARM: ldr pc,[pc,#-4]
    p[1]=reinterpret_cast<uintptr_t>(target);
    __builtin___clear_cache(reinterpret_cast<char*>(p),reinterpret_cast<char*>(p+2));
}
static bool relocate(const Elf32_Shdr *section,const Elf32_Sym *symbols,const char *strings) {
    auto *rels=reinterpret_cast<const Elf32_Rel*>(image.data()+section->sh_offset);
    bool ok=true;
    for(size_t i=0;i<section->sh_size/sizeof(*rels);++i) {
        const auto &r=rels[i]; unsigned type=ELF32_R_TYPE(r.r_info);
        const auto &symbol=symbols[ELF32_R_SYM(r.r_info)];
        const char *name=strings+symbol.st_name;
        uintptr_t value=symbol.st_value;
        if(symbol.st_shndx==SHN_UNDEF && *name) {
            void *resolved=compat_symbol(name);
            if(!resolved && ELF32_ST_BIND(symbol.st_info)!=STB_WEAK) {
                port_log("UNRESOLVED %s",name);ok=false;
            }
            value=reinterpret_cast<uintptr_t>(resolved);
        }
        auto *where=reinterpret_cast<uint32_t*>(r.r_offset);
        if(type==R_ARM_GLOB_DAT || type==R_ARM_JUMP_SLOT) *where=value;
        else if(type==R_ARM_ABS32) *where+=value;
        else if(type!=R_ARM_NONE) {port_log("Unsupported ARM relocation %u at %08x",type,r.r_offset);ok=false;}
    }
    return ok;
}
bool load_engine(const char *path) {
    log_file=fopen("port-log.txt","w");
    struct sigaction action={}; action.sa_sigaction=crash;action.sa_flags=SA_SIGINFO;
    sigemptyset(&action.sa_mask);sigaction(SIGSEGV,&action,nullptr);sigaction(SIGBUS,&action,nullptr);sigaction(SIGILL,&action,nullptr);sigaction(SIGABRT,&action,nullptr);
    FILE *f=fopen(path,"rb");if(!f){port_log("Cannot open engine %s",path);return false;}
    fseek(f,0,SEEK_END);long size=ftell(f);rewind(f);
    if(size!=3976092){port_log("Unsupported executable size %ld",size);fclose(f);return false;}
    image.resize(size);size_t count=fread(image.data(),1,size,f);fclose(f);
    if(count!=static_cast<size_t>(size))return false;
    const auto *eh=reinterpret_cast<const Elf32_Ehdr*>(image.data());
    if(memcmp(eh->e_ident,ELFMAG,SELFMAG)||eh->e_machine!=EM_ARM||eh->e_type!=ET_EXEC){port_log("Unsupported ELF");return false;}
    const auto *ph=reinterpret_cast<const Elf32_Phdr*>(image.data()+eh->e_phoff);
    for(unsigned i=0;i<eh->e_phnum;++i)if(ph[i].p_type==PT_LOAD) {
        uintptr_t start=ph[i].p_vaddr&~4095u;
        uintptr_t end=(ph[i].p_vaddr+ph[i].p_memsz+4095u)&~4095u;
        // Native bridges reserve 0x10000..0x12000 for their own dispatch data.
        // The first executable section starts at 0x143ec. ELF headers and
        // dynamic symbols below it are read from image, never executed.
        if(start==0x10000)start=0x12000;
        // Without MAP_FIXED: never replace mappings that belong to Android.
        void *mapped=mmap(reinterpret_cast<void*>(start),end-start,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        if(mapped!=reinterpret_cast<void*>(start)) {
            if(mapped!=MAP_FAILED)munmap(mapped,end-start);
            port_log("Could not reserve engine address %08x (returned %p, errno=%d: %s)",static_cast<unsigned>(start),mapped,errno,strerror(errno));
            FILE *maps=fopen("/proc/self/maps","r");
            if(maps){char line[512];for(int n=0;n<12&&fgets(line,sizeof line,maps);++n)port_log("Mapping: %s",line);fclose(maps);}
            return false;
        }
        uintptr_t copy_start=std::max(start,static_cast<uintptr_t>(ph[i].p_vaddr));
        size_t skipped=copy_start-ph[i].p_vaddr;
        memcpy(reinterpret_cast<void*>(copy_start),image.data()+ph[i].p_offset+skipped,ph[i].p_filesz-skipped);
        port_log("Mapped engine %08x..%08x",static_cast<unsigned>(start),static_cast<unsigned>(end));
    }
    const auto *sh=reinterpret_cast<const Elf32_Shdr*>(image.data()+eh->e_shoff);
    bool ok=true;
    for(unsigned i=0;i<eh->e_shnum;++i)if(sh[i].sh_type==SHT_REL) {
        const auto &sym_section=sh[sh[i].sh_link];
        const auto *symbols=reinterpret_cast<const Elf32_Sym*>(image.data()+sym_section.sh_offset);
        const char *strings=reinterpret_cast<const char*>(image.data()+sh[sym_section.sh_link].sh_offset);
        ok=relocate(&sh[i],symbols,strings)&&ok;
    }
    if(!ok)return false;
    if(!install_platform_hooks())return false;
    __builtin___clear_cache(reinterpret_cast<char*>(0x12000),reinterpret_cast<char*>(0x3cb000));
    if(mprotect(reinterpret_cast<void*>(0x12000),0x3b9000,PROT_READ|PROT_EXEC)!=0){
        port_log("Cannot protect game code as executable");return false;
    }
    return true;
}
void initialize_engine() {
    // Original .init_array. These functions initialize the game and its static C++ library.
    auto *init=reinterpret_cast<uint32_t*>(0x3cb3a8);
    for(unsigned i=0;i<23;++i)if(init[i] && init[i]!=0xffffffffu) {
        port_log("Static initializer %u: %08x",i,init[i]);
        reinterpret_cast<void(*)()>(init[i])();
    }
}
int run_engine(int argc,char **argv) {
    port_log("Entering original ARM game main at 0001afe8");
    return reinterpret_cast<int(*)(int,char**)>(0x1afe8)(argc,argv);
}
