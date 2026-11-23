#include "port.h"
#include <vector>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <tuple>
#include <zlib.h>

namespace {
using Key=std::tuple<int,int,int,uint32_t>;
std::map<Key,std::vector<unsigned char>> cache;
size_t cache_bytes=0;
struct Sample {int colour;int indices[4];float fraction;};
std::vector<Sample> coordinates(int size,int scale){
    std::vector<Sample> result(size*scale);
    for(int i=0;i<size*scale;i++){
        float p=(i+.5f)/scale-.5f;
        result[i].colour=static_cast<int>(std::floor(p));
        result[i].fraction=p-result[i].colour;
        for(int j=0;j<4;j++){
            int at=static_cast<int>(std::floor(p+(j+.5f)/4-.5f));
            result[i].indices[j]=at>=0&&at<size?at:-1;
        }
    }
    return result;
}
void render_opaque(int w,int h,int ow,int oh,const unsigned char *rgba,
                   const std::vector<Sample>& xs,const std::vector<Sample>& ys,
                   std::vector<unsigned char>& output){
    for(int y=0;y<oh;y++)for(int x=0;x<ow;x++){
        int colour[3]={};
        for(int j=0;j<2;j++)for(int i=0;i<2;i++){
            int sx=std::clamp(xs[x].colour+i,0,w-1),sy=std::clamp(ys[y].colour+j,0,h-1);
            float weight=(i?xs[x].fraction:1-xs[x].fraction)*(j?ys[y].fraction:1-ys[y].fraction);
            const auto *pixel=rgba+(static_cast<size_t>(sy)*w+sx)*4;
            for(int c=0;c<3;c++)colour[c]+=static_cast<int>(std::round(pixel[c]*weight));
        }
        auto *dest=output.data()+(static_cast<size_t>(y)*ow+x)*4;
        for(int c=0;c<3;c++)dest[c]=std::min(255,colour[c]);
        dest[3]=255;
    }
    const auto original=output;
    for(int y=1;y<oh-1;y++)for(int x=1;x<ow-1;x++){
        size_t offset=(static_cast<size_t>(y)*ow+x)*4;
        for(int c=0;c<3;c++){
            int sum=0;
            for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++)sum+=original[offset+(j*ow+i)*4+c];
            output[offset+c]=std::clamp(2*original[offset+c]-sum/9,0,255);
        }
    }
}
}

// Produce the same high-resolution text as the previous renderer without
// round-tripping the full texture through Java or writing it to disk.
bool native_text_image(int w,int h,int scale,const void *source,std::vector<unsigned char>&output){
    if(!source||w<=0||h<=0||scale<=1)return false;
    const auto *rgba=static_cast<const unsigned char*>(source);
    bool opaque=true;
    for(size_t i=0;i<static_cast<size_t>(w)*h;i++)if(rgba[i*4+3]!=255){opaque=false;break;}
    const size_t bytes=static_cast<size_t>(w)*h*scale*scale*4;
    Key key{w,h,scale,crc32(0,rgba,static_cast<size_t>(w)*h*4)};
    auto found=cache.find(key);
    if(found!=cache.end()){output=found->second;return true;}
    Uint64 started=SDL_GetTicks();
    const int ow=w*scale,oh=h*scale;
    output.assign(bytes,0);
    auto xs=coordinates(w,scale),ys=coordinates(h,scale);
    // The former Java implementation did 16 source lookups and floor calls
    // per output pixel. Resolve the same separable 4x4 footprint once per row.
    unsigned char coverage_alpha[17];
    for(int count=0;count<=16;count++){
        float coverage=count/16.f;
        float shaped=coverage<=.08f?0:coverage>=.62f?1:(coverage-.08f)/.54f;
        coverage_alpha[count]=static_cast<unsigned char>(std::round(shaped*255.f));
    }
    if(opaque)render_opaque(w,h,ow,oh,rgba,xs,ys,output);
    else {
      std::vector<unsigned char> vertical(w);
      for(int y=0;y<oh;y++){
        std::fill(vertical.begin(),vertical.end(),0);
        for(int j=0;j<4;j++)if(ys[y].indices[j]>=0){
            const auto *row=rgba+static_cast<size_t>(ys[y].indices[j])*w*4;
            for(int x=0;x<w;x++)vertical[x]+=row[x*4+3]>=96;
        }
        for(int x=0;x<ow;x++){
            int count=0;for(int j=0;j<4;j++)if(xs[x].indices[j]>=0)count+=vertical[xs[x].indices[j]];
            int alpha=coverage_alpha[count];
            if(!alpha)continue;
            auto *dest=output.data()+(static_cast<size_t>(y)*ow+x)*4;
            const auto *colour=rgba+(static_cast<size_t>(std::clamp(ys[y].colour,0,h-1))*w+std::clamp(xs[x].colour,0,w-1))*4;
            memcpy(dest,colour,3);dest[3]=alpha;
        }
      }
    }
    // Keep repeated menu labels and glyph atlases without growing unbounded.
    constexpr size_t budget=8*1024*1024;
    if(bytes<=budget){
        if(cache_bytes+bytes>budget){cache.clear();cache_bytes=0;}
        cache.emplace(key,output);cache_bytes+=bytes;
    }
    port_log("Text reconstruction %dx%d scale=%d %llu ms",w,h,scale,static_cast<unsigned long long>(SDL_GetTicks()-started));
    return true;
}
