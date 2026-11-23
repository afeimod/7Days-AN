// The shipped port only recognizes a small Latin bitmap-hash dictionary.
// Unknown glyphs used to become codepoint zero and disappear. Give each exact
// bitmap a stable private codepoint shared by script decoding and font atlases.
// The bitmaps themselves are preserved, so no OCR or replacement font is needed.
#include "port.h"
#include <string>
#include <map>
#include <string.h>
#include <mutex>

static std::mutex glyph_mutex;
static std::map<std::string,uint32_t> glyphs;
struct GameString {char *data;uint32_t length;char local[16];};
struct Glyph {uint32_t width,height;char *begin,*end,*capacity;};
static uint32_t glyph_code(const Glyph *g) {
    GameString hash;
    reinterpret_cast<void(*)(GameString*,const Glyph*)>(0x49740)(&hash,g);
    struct ReleaseHash{GameString *value;~ReleaseHash(){reinterpret_cast<void(*)(GameString*)>(0x210c8)(value);}}release{&hash};
    // Keep the engine's punctuation and whitespace semantics.
    const uint32_t *table=reinterpret_cast<const uint32_t*>(0x3cb4b8);
    for(int i=0;i<24;i++)if(!strcmp(hash.data,reinterpret_cast<const char*>(table[2*i])))return table[2*i+1];
    std::string key(reinterpret_cast<const char*>(&g->width),8);
    key.append(g->begin,g->end-g->begin);
    std::lock_guard<std::mutex> lock(glyph_mutex);
    auto found=glyphs.find(key);
    if(found!=glyphs.end())return found->second;
    uint32_t code=0xf0000+glyphs.size();
    glyphs.emplace(key,code);
    if(glyphs.size()==1 || glyphs.size()%256==0)port_log("Bitmap font: %u additional glyphs preserved",static_cast<unsigned>(glyphs.size()));
    return code;
}
void install_bitmap_font(){port_patch(0x49cd0,reinterpret_cast<void*>(glyph_code));}
