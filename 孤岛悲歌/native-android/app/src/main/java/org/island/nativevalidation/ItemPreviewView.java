package org.island.nativevalidation;

import android.content.Context;
import android.graphics.*;
import android.opengl.*;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.nio.*;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Real supplied UISD2 geometry and skin, not a magnified inventory icon. */
public final class ItemPreviewView extends GLSurfaceView implements GLSurfaceView.Renderer {
    private volatile int requested=-1;private int current=-2,program,texture,count;
    private FloatBuffer mesh;private float extent=1,cx,cy,cz,aspect=1;
    private final float[] projection=new float[16],view=new float[16],model=new float[16],pv=new float[16],mvp=new float[16];
    public ItemPreviewView(Context c){super(c);setEGLContextClientVersion(2);setZOrderOnTop(true);setEGLConfigChooser(8,8,8,8,16,0);getHolder().setFormat(PixelFormat.TRANSLUCENT);setRenderer(this);setContentDescription("物品三维预览");}
    public void item(int picpos){requested=picpos;}
    public void onSurfaceCreated(GL10 gl,EGLConfig config){
        String vs="uniform mat4 mvp;attribute vec3 position;attribute vec2 uv;varying vec2 vuv;void main(){gl_Position=mvp*vec4(position,1.0);vuv=uv;}";
        String fs="precision mediump float;uniform sampler2D skin;varying vec2 vuv;void main(){vec4 c=texture2D(skin,vuv);if(c.a<0.5)discard;gl_FragColor=vec4(c.rgb/max(c.a,0.001),1.0);}";
        program=GLES20.glCreateProgram();GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,vs));GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,fs));GLES20.glLinkProgram(program);current=-2;texture=0;
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glClearColor(0,0,0,0);
    }
    private int shader(int type,String source){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);return s;}
    public void onSurfaceChanged(GL10 gl,int w,int h){GLES20.glViewport(0,0,w,h);aspect=(float)w/Math.max(1,h);}
    public void onDrawFrame(GL10 gl){
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        if(requested!=current){current=requested;load(current);}if(mesh==null)return;
        float half=extent*.72f;Matrix.orthoM(projection,0,-half*Math.max(1,aspect),half*Math.max(1,aspect),-half/Math.min(1,aspect),half/Math.min(1,aspect),-extent*4,extent*4);
        Matrix.setLookAtM(view,0,0,-extent*2,extent*.18f,0,0,0,0,0,1);
        Matrix.setIdentityM(model,0);Matrix.rotateM(model,0,(SystemClock.uptimeMillis()%12000)*.03f,0,0,1);Matrix.translateM(model,0,-cx,-cy,-cz);
        Matrix.multiplyMM(pv,0,projection,0,view,0);Matrix.multiplyMM(mvp,0,pv,0,model,0);
        GLES20.glUseProgram(program);GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"mvp"),1,false,mvp,0);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"skin"),0);
        int p=GLES20.glGetAttribLocation(program,"position"),u=GLES20.glGetAttribLocation(program,"uv");GLES20.glEnableVertexAttribArray(p);GLES20.glEnableVertexAttribArray(u);mesh.position(0);GLES20.glVertexAttribPointer(p,3,GLES20.GL_FLOAT,false,20,mesh);mesh.position(3);GLES20.glVertexAttribPointer(u,2,GLES20.GL_FLOAT,false,20,mesh);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,count);
    }
    private void load(int i){
        mesh=null;if(texture!=0){GLES20.glDeleteTextures(1,new int[]{texture},0);texture=0;}if(i<0)return;
        try(InputStream in=getContext().getAssets().open("ui/preview-"+i+".mesh");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);ByteBuffer data=ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);count=data.getInt();mesh=ByteBuffer.allocateDirect(count*20).order(ByteOrder.nativeOrder()).asFloatBuffer();float[] lo={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE},hi={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
            for(int v=0;v<count;v++)for(int k=0;k<5;k++){float value=data.getFloat();mesh.put(value);if(k<3){lo[k]=Math.min(lo[k],value);hi[k]=Math.max(hi[k],value);}}
            cx=(lo[0]+hi[0])/2;cy=(lo[1]+hi[1])/2;cz=(lo[2]+hi[2])/2;extent=Math.max(1,Math.max(hi[0]-lo[0],Math.max(hi[1]-lo[1],hi[2]-lo[2])));mesh.position(0);
            int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);texture=ids[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,OriginalUi.bitmap(getContext(),"preview-"+i),0);
        }catch(Exception error){mesh=null;Log.e("IslandUi","Item preview "+i,error);}
    }
}
