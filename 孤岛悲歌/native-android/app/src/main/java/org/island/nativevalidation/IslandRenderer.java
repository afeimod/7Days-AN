package org.island.nativevalidation;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.LinkedHashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Native Android/OpenGL ES scene renderer. No Symbian/CPU emulator is present. */
public final class IslandRenderer implements GLSurfaceView.Renderer,GameEngine.Host {
    private static final float INTERACTION_REACH=30f;
    private static final String VERTEX = "uniform mat4 mvp; attribute vec3 position; attribute vec2 uv; attribute vec2 luv; varying vec2 vuv; varying vec2 vl; void main(){gl_Position=mvp*vec4(position,1.0);vuv=uv;vl=luv;}";
    private static final String FRAGMENT = "precision mediump float; uniform sampler2D diffuse; uniform sampler2D lightmap; uniform float lit; uniform float softLight; varying vec2 vuv; varying vec2 vl; void main(){vec4 c=texture2D(diffuse,vuv);if(c.a<0.5)discard;vec3 rgb=c.rgb/max(c.a,0.001);vec3 l=texture2D(lightmap,vl).rgb;vec3 light=mix(vec3(0.86),min(l*1.8+0.40,vec3(1.0)),lit);rgb=rgb*light;rgb+=vec3(clamp(softLight,0.0,1.0)*0.16);gl_FragColor=vec4(min(rgb,vec3(1.0)),1.0);} ";
    private static final String SOFT_VERTEX = "attribute vec2 position; attribute vec2 texcoord; varying vec2 uv; void main(){gl_Position=vec4(position,0.0,1.0);uv=texcoord;}";
    private static final String SOFT_FRAGMENT = "precision mediump float; uniform sampler2D scene; uniform vec2 texel; uniform float amount; varying vec2 uv; void main(){vec4 center=texture2D(scene,uv);vec4 blur=center*0.40;blur+=(texture2D(scene,uv+vec2(texel.x,0.0))+texture2D(scene,uv-vec2(texel.x,0.0))+texture2D(scene,uv+vec2(0.0,texel.y))+texture2D(scene,uv-vec2(0.0,texel.y)))*0.15;gl_FragColor=mix(center,blur,amount);} ";
    private final AssetManager assets;
    private final boolean[] keys = new boolean[8];
    private volatile float analogForward,analogRight;
    private volatile float controllerYaw,controllerPitch;
    private final float[] projection=new float[16], view=new float[16], mvp=new float[16];
    private final float[] modelMatrix=new float[16],drawMatrix=new float[16];
    private final WorldPhysics physics=new WorldPhysics();
    private final ThirdPersonCamera camera=new ThirdPersonCamera();
    private final CinematicCamera cinematic=new CinematicCamera();
    private final PlayerAnimation playerAnimation=new PlayerAnimation();
    private Batch player;
    private Batch slingRubber;
    private float[][] handWeights;
    private int[] handMarkers;
    private FloatBuffer posedPlayer;
    private final HashMap<Integer,Batch> handItems=new HashMap<>();
    private final HashMap<String,String> weaponTextures=new HashMap<>();
    private float weaponTime=-1;
    private boolean weaponRanged;
    private int locomotion,playerFrame;
    public GameEngine game;
    public GameAudio audio;
    private final GameplaySounds sounds=new GameplaySounds(name->{if(audio!=null)audio.effect(name);});
    public volatile String dialogue="",interaction="",scriptError="";
    public volatile int puzzleId=-1;
    public volatile int movieId=-1;
    public volatile int pictureId=-1;
    public volatile int uiDepth;
    private int targetId,entryId;
    private float entryAngle;
    private float[] afterLoad;
    private java.util.Properties afterLoadState;
    private float lastSafeX,lastSafeY,lastSafeZ;
    private final ArrayList<Tween> tweens=new ArrayList<>();
    private final ArrayList<SlingshotFlight> pellets=new ArrayList<>();
    private Batch pelletMesh;
    private GameEngine.Entity held;
    public volatile boolean aiming;
    public volatile boolean pickMode;
    public volatile String actionHint="调查 / 使用";
    private float attackCooldown;
    private boolean scriptedPlayerMoving;
    private float jumpForward;
    private static final class Tween {GameEngine.Entity actor;float sx,sy,sz,tx,ty,tz,time,duration,pivotX,pivotY,sweep;int kind;}
    private final ArrayList<Batch> batches=new ArrayList<>();
    private final HashMap<String,Integer> textures=new HashMap<>();
    private String[] changeTextures=new String[0];
    private final ArrayList<Integer> lightmaps=new ArrayList<>();
    private float[][] planes=new float[0][];
    private int[][] brushes=new int[0][];
    private int program, white, placeholder, mvpLocation, positionLocation, uvLocation, lightUvLocation, litLocation, softLightLocation;
    private int softProgram,softPosition,softTexcoord,softScene,softTexel,softAmount,sceneFramebuffer,sceneTexture,sceneDepth,sceneWidth,sceneHeight;
    private FloatBuffer softQuad;
    public volatile float softLight=.28f;
    private int width=1,height=1;
    private long previous, statusTime;
    private int frames;
    private volatile int pending;
    private float[] restore;
    private String error;
    public volatile int level=1;
    public float x,y,z,yaw=90,pitch;
    public volatile String status="正在加载…";

    private static final class Batch {FloatBuffer vertices;FloatBuffer[] frames;int owner,count,diffuse,light;boolean baked;}
    public IslandRenderer(AssetManager assets){this.assets=assets;}
    public void setSoftLight(float value){softLight=Math.max(0f,Math.min(1f,value));}
    public synchronized void key(int index,boolean down){keys[index]=down;}
    public synchronized void releaseKeys(){for(int i=0;i<keys.length;i++)keys[i]=false;analogForward=analogRight=controllerYaw=controllerPitch=0;}
    public synchronized void move(float forward,float right){analogForward=Math.max(-1,Math.min(1,forward));analogRight=Math.max(-1,Math.min(1,right));}
    public synchronized void controllerLook(float horizontal,float vertical){controllerYaw=horizontal;controllerPitch=vertical;}
    public synchronized void look(float dx,float dy){yaw-=dx;pitch=Math.max(-80,Math.min(80,pitch-dy));}
    public synchronized void requestLevel(int n){restore=null;pending=Math.max(1,Math.min(18,n));releaseKeys();}
    public synchronized void requestRestore(int n,float[] state){restore=state;pending=n;releaseKeys();}
    public void action(){releaseKeys();if(game==null)return;if(!game.dialog.isEmpty()){game.advance();return;}if(game.busy())return;if(held!=null){held=null;physics.held=null;playerAnimation.play(18,0,0);return;}if(aiming){attack();return;}GameEngine.Entity e=game.find(targetId);if(e!=null&&"1".equals(e.data.get("pull"))){if(physics.crouched)return;held=e;physics.held=e;playerAnimation.play(15,0,2);return;}if(shouldSwingPickaxe(e)){attack();return;}if(targetId!=0)game.interact(targetId,false);}
    private boolean shouldSwingPickaxe(GameEngine.Entity target){
        if(game.selected!=20030||!game.inventory.contains(20030))return false;
        if(target==null)return true;
        if("1".equals(target.data.get("pull"))||game.prioritizesAimedPickup(target))return false;
        GameEngine.Rule rule=game.interaction(target.id);
        if(rule!=null&&rule.n("item",-1)==game.selected)return false;
        // A BSP investigation volume can surround an entire plaster wall.
        // Its nearer face must not hide the individual destructible pieces.
        return target.ai!=0&&target.auto>=3||attackTarget(false)!=0;
    }
    public void equipPickaxe(){if(game==null||game.busy()||held!=null)return;aiming=false;if(game.inventory.contains(20030)){game.selected=20030;pickMode=true;}}
    public void toggleSlingshot(){if(game==null||game.busy()||held!=null||!game.inventory.contains(20100))return;pickMode=false;aiming=!aiming;if(aiming)game.selected=20100;}
    public void selectItem(int id){if(game==null)return;game.selected=id;aiming=false;pickMode=id==20030;}
    public void jump(){if(game!=null&&!game.busy()&&held==null&&!aiming&&physics.grounded&&!physics.crouched){physics.jump();jumpForward=.82f;sounds.jump();}}
    public void crouch(){if(game==null||game.busy()||held!=null||aiming)return;if(physics.crouched){if(physics.canStand(x,y,z+24)){z+=24;physics.crouched=false;}}else{z-=24;physics.crouched=true;}}
    public void attack(){
        if(game==null||game.busy()||held!=null||attackCooldown>0)return;
        boolean ranged=game.selected==20100&&aiming;
        if(!ranged&&game.selected!=20030)return;
        if(!game.inventory.contains(game.selected))return;
        if(ranged&&game.ammo<=0){game.attack(-1);return;}
        int clip=physics.crouched?38:39;
        playerAnimation.play(clip,0,physics.crouched?2:1);
        attackCooldown=.55f;weaponTime=0;weaponRanged=ranged;
        int id=attackTarget(ranged);
        if(ranged){
            float a=(float)Math.toRadians(yaw),p=(float)Math.toRadians(pitch),c=(float)Math.cos(a),s=(float)Math.sin(a),cp=(float)Math.cos(p),sp=(float)Math.sin(p);
            float tx=x+c*cp*650,ty=y+s*cp*650,tz=z+sp*650;boolean impact=false;
            GameEngine.Entity target=game.find(id);
            if(target!=null){float[] point=WorldPhysics.surfacePoint(x,y,z,target);tx=point[0];ty=point[1];tz=point[2];impact=true;}
            else{WorldPhysics.Hit wall=physics.cameraTrace(x,y,z,tx,ty,tz,.5f);impact=wall.fraction<1;tx=x+(tx-x)*wall.fraction;ty=y+(ty-y)*wall.fraction;tz=z+(tz-z)*wall.fraction;}
            pellets.add(new SlingshotFlight(x+c*(cp*23+sp*13),y+s*(cp*23+sp*13),z+sp*23-cp*13,tx,ty,tz,impact));
        }
        boolean hit=id!=0&&game.attack(id);if(id==0&&ranged)game.fireSlingshot();if(!ranged)sounds.weapon(false,hit);
    }
    private int attackTarget(boolean ranged){
        float a=(float)Math.toRadians(yaw),c=(float)Math.cos(a),s=(float)Math.sin(a),p=(float)Math.toRadians(pitch),cp=(float)Math.cos(p),sp=(float)Math.sin(p);
        float best=ranged?650:95;int id=0;
        for(GameEngine.Entity e:game.entities.values())if(!e.removed&&e.bounds!=null&&((e.ai!=0&&e.auto>=3)||(ranged&&e.auto==2&&game.interaction(e.id)!=null))){
            float[] point=WorldPhysics.surfacePoint(x,y,z,e);
            float vx=point[0]-x,vy=point[1]-y,vz=point[2]-z,l=(float)Math.sqrt(vx*vx+vy*vy),distance=(float)Math.sqrt(l*l+vz*vz);
            boolean close=!ranged&&distance<32;
            // A near-surface point can lie directly below the eye (or at the
            // eye inside an enemy's bounds). It must not capture an aimed shot
            // just because melee would reach it. Aim at the object's centre.
            float dot;
            if(ranged){float[] b=e.bounds;float ax=(b[0]+b[3])*.5f+e.dx-x,ay=(b[1]+b[4])*.5f+e.dy-y,az=(b[2]+b[5])*.5f+e.dz-z;
                float length=(float)Math.sqrt(ax*ax+ay*ay+az*az);dot=length<.001f?-1:(ax*c*cp+ay*s*cp+az*sp)/length;
            }else dot=l<.001f?1:(vx*c+vy*s)/l;
            if(distance<best&&(close||dot>(ranged?.97f:.8f))&&physics.visibleTo(x,y,z,e)){best=distance;id=e.id;}
        }
        return id;
    }
    public void scene(int map,int entry,float angle){pending=map;entryId=entry;entryAngle=angle;releaseKeys();tweens.clear();pellets.clear();cinematic.active=false;held=null;aiming=pickMode=false;attackCooldown=0;weaponTime=-1;physics.held=null;physics.support=null;physics.crouched=false;jumpForward=0;}
    public void report(String message){Log.i("IslandGame",message);}
    public String motionState(){
        StringBuilder out=new StringBuilder("camview,"+cinematic.snapshot()+";");for(Tween t:tweens)out.append(t.kind==3?"camera":t.actor==null?"player":""+t.actor.originalId).append(',').append(t.sx).append(',').append(t.sy).append(',').append(t.sz).append(',').append(t.tx).append(',').append(t.ty).append(',').append(t.tz).append(',').append(t.time).append(',').append(t.duration).append(',').append(t.kind).append(',').append(t.pivotX).append(',').append(t.pivotY).append(',').append(t.sweep).append(';');return out.toString();
    }
    public void restoreMotionState(String state){
        tweens.clear();cinematic.active=false;if(state==null||state.isEmpty())return;
        for(String line:state.split(";")){if(line.startsWith("camview,")){cinematic.restore(line.substring(8));continue;}String[] v=line.split(",");if(v.length!=9&&v.length!=13)continue;boolean player="player".equals(v[0]),cam="camera".equals(v[0]);GameEngine.Entity e=player||cam?null:game.entities.get(GameEngine.num(v[0],0));if(!player&&!cam&&(e==null||e.removed))continue;Tween t=new Tween();t.actor=e;t.sx=GameEngine.flt(v[1]);t.sy=GameEngine.flt(v[2]);t.sz=GameEngine.flt(v[3]);t.tx=GameEngine.flt(v[4]);t.ty=GameEngine.flt(v[5]);t.tz=GameEngine.flt(v[6]);t.time=GameEngine.flt(v[7]);t.duration=Math.max(.05f,GameEngine.flt(v[8]));if(v.length==13){t.kind=GameEngine.num(v[9],0);t.pivotX=GameEngine.flt(v[10]);t.pivotY=GameEngine.flt(v[11]);t.sweep=GameEngine.flt(v[12]);}tweens.add(t);}
    }
    public void effect(String command,String[] args){
        if(command.equals("follow")&&args.length>0){if(GameEngine.num(args[0],1)==0)cinematic.begin(camera,x,y,z,yaw,pitch);else{cinematic.active=false;tweens.removeIf(t->t.kind==3);camera.reset();}}
        if(command.equals("gameplay-sound")&&args.length>0&&audio!=null)audio.effect(args[0]);
        if(command.equals("playback")&&args.length>0&&audio!=null)audio.music(GameEngine.num(args[0],0));
        if(command.equals("playsound")&&args.length>0&&audio!=null)audio.effect(GameEngine.num(args[0],-1));
    }
    public float motion(GameEngine.Entity actor,int actorId,String command,String[] args){
        if(command.equals("setaction")){
            if(actorId==0&&args.length>=4)return playerAnimation.play(GameEngine.num(args[1],0),GameEngine.num(args[2],0),GameEngine.num(args[3],0));
            return 0;
        }
        if(actorId==2){
            cinematic.begin(camera,x,y,z,yaw,pitch);
            if(command.equals("toward")&&args.length>0){GameEngine.Entity target=game.find(GameEngine.num(args[0],0));boolean player=GameEngine.num(args[0],0)==0;if(target==null&&!player)return 0;
                cinematic.tx=player?x:target.x+target.dx;cinematic.ty=player?y:target.y+target.dy;cinematic.tz=(player?z-60:target.z+target.dz)+(args.length>1?GameEngine.flt(args[1]):0);return .1f;}
            float[] to=null;
            if(command.equals("moveto")&&args.length>=3){int id=GameEngine.num(args[2],0);GameEngine.Entity target=game.find(id);if(target==null&&id!=0)return 0;
                to=cinematic.destination(id==0?x:target.x+target.dx,id==0?y:target.y+target.dy,id==0?z-60:target.z+target.dz,args.length>3?GameEngine.flt(args[3]):0,args.length>4?GameEngine.flt(args[4]):0);
            }else if(command.equals("move")&&args.length>=6){float d=GameEngine.flt(args[5]);to=new float[]{cinematic.x+GameEngine.flt(args[0])*d,cinematic.y+GameEngine.flt(args[2])*d,cinematic.z-GameEngine.flt(args[1])*d};}
            if(to==null)return 0;Tween t=new Tween();t.kind=3;t.sx=cinematic.x;t.sy=cinematic.y;t.sz=cinematic.z;t.tx=to[0];t.ty=to[1];t.tz=to[2];float speed=Math.max(20,Math.abs(GameEngine.flt(args[command.equals("move")?3:0]))*10);
            t.duration=Math.max(.1f,Math.min(8,(float)Math.sqrt((t.tx-t.sx)*(t.tx-t.sx)+(t.ty-t.sy)*(t.ty-t.sy)+(t.tz-t.sz)*(t.tz-t.sz))/speed));tweens.removeIf(old->old.kind==3);tweens.add(t);return t.duration;
        }
        if(actorId!=0&&actor==null){Log.w("IslandNative","Missing motion actor "+actorId+" for "+command);return 0;}
        float[] a=new float[args.length];for(int i=0;i<a.length;i++)a[i]=GameEngine.flt(args[i]);
        float px=actor==null?x:actor.dx,py=actor==null?y:actor.dy,pz=actor==null?z:actor.dz;
        if(command.equals("toward")){
            if(args.length>0){int target=GameEngine.num(args[0],0);GameEngine.Entity e=game.find(target);
                if(e!=null&&actor==null)yaw=(float)Math.toDegrees(Math.atan2(e.y+e.dy-y,e.x+e.dx-x));
                else if(actor!=null&&"1".equals(actor.data.get("npc"))&&(e!=null||target==0)){
                    float tx=e==null?x:e.x+e.dx,ty=e==null?y:e.y+e.dy;
                    actor.rotation=(float)Math.toDegrees(Math.atan2(ty-actor.y-actor.dy,tx-actor.x-actor.dx))-90-GameEngine.flt(actor.data.get("angle"));
                }
            }return .05f;
        }
        if(command.equals("rotate")&&actor!=null&&a.length>=6){
            Tween t=new Tween();t.actor=actor;t.kind=1;t.sx=actor.rotationX;t.sy=actor.rotationY;t.sz=actor.rotation;
            float sweep=a[5]*360/4096*(actor.reverse?-1:1);
            t.tx=t.sx+a[0]*sweep;t.ty=t.sy+a[2]*sweep;t.tz=t.sz-a[1]*sweep;
            t.duration=Math.max(.05f,Math.abs(a[5])/Math.max(1,Math.abs(a[3])*10));
            tweens.add(t);return t.duration;
        }
        if(command.equals("round")&&actor!=null&&a.length>=4){
            GameEngine.Entity pivot=game.find(GameEngine.num(args[0],0));if(pivot==null)return 0;
            Tween t=new Tween();t.actor=actor;t.kind=2;t.sx=actor.dx;t.sy=actor.dy;t.sz=actor.dz;
            t.pivotX=pivot.x+pivot.dx;t.pivotY=pivot.y+pivot.dy;t.sweep=a[3]*360/4096*(actor.reverse?-1:1);
            t.duration=Math.max(.05f,Math.abs(a[3])/Math.max(1,Math.abs(a[1])*10));tweens.add(t);return t.duration;
        }
        float tx=px,ty=py,tz=pz,speed=100;
        if(command.equals("moveto")&&a.length>=3){
            GameEngine.Entity e=game.find(GameEngine.num(args[2],0));if(e==null)return 0;
            if(actor==null){tx=e.x+e.dx;ty=e.y+e.dy;tz=physics.groundEye(tx,ty,e.z+e.dz+36);}
            else{tx=e.x-actor.x;ty=e.y-actor.y;tz="1".equals(actor.data.get("npc"))?physics.groundEye(e.x,e.y,e.z+60)-60-actor.z:e.z-actor.z;}
            speed=Math.max(60,Math.abs(a[0])*10);
        }else if(command.equals("move")&&a.length>=6){
            float distance=a[5]*(actor!=null&&actor.reverse?-1:1);speed=Math.max(40,Math.abs(a[3])*10);
            if(a[0]==0&&a[1]==0&&a[2]==0){float angle=(float)Math.toRadians(actor==null?yaw:GameEngine.flt(actor.data.get("angle"))+actor.rotation);tx+=Math.cos(angle)*distance;ty+=Math.sin(angle)*distance;}
            else{tx+=a[0]*distance;ty+=a[2]*distance;tz-=a[1]*distance;}
        }else return 0;
        float distance=(float)Math.sqrt((tx-px)*(tx-px)+(ty-py)*(ty-py)+(tz-pz)*(tz-pz));
        Tween t=new Tween();t.actor=actor;t.sx=px;t.sy=py;t.sz=pz;t.tx=tx;t.ty=ty;t.tz=tz;t.duration=Math.max(.05f,Math.min(6,distance/speed));
        for(Iterator<Tween> it=tweens.iterator();it.hasNext();){Tween old=it.next();if(old.kind==0&&old.actor==actor)it.remove();}
        tweens.add(t);return t.duration;
    }
    private void updateTween(float dt){
        scriptedPlayerMoving=false;
        for(Iterator<Tween> it=tweens.iterator();it.hasNext();){Tween t=it.next();if(t.actor!=null&&t.actor.frozen)continue;t.time+=dt;float u=Math.min(1,t.time/t.duration);
            float px=t.sx+(t.tx-t.sx)*u,py=t.sy+(t.ty-t.sy)*u,pz=t.sz+(t.tz-t.sz)*u;
            if(t.kind==3){cinematic.x=px;cinematic.y=py;cinematic.z=pz;if(u>=1)it.remove();continue;}
            if(t.kind==1){t.actor.rotationX=px;t.actor.rotationY=py;t.actor.rotation=pz;if(u>=1)it.remove();continue;}
            if(t.kind==2){
                float angle=(float)Math.toRadians(t.sweep*u),c=(float)Math.cos(angle),s=(float)Math.sin(angle);
                float vx=t.actor.x+t.sx-t.pivotX,vy=t.actor.y+t.sy-t.pivotY;
                px=t.pivotX+vx*c-vy*s-t.actor.x;py=t.pivotY+vx*s+vy*c-t.actor.y;pz=t.sz;
            }
            if(t.actor==null){
                float movedX=px-x,movedY=py-y;
                x=px;y=py;z=pz;
                float minTravel=1.15f*dt;
                if(movedX*movedX+movedY*movedY>minTravel*minTravel){
                    float angle=(float)Math.toRadians(yaw),c=(float)Math.cos(angle),s=(float)Math.sin(angle);
                    float forward=movedX*c+movedY*s,strafe=movedX*s-movedY*c;
                    locomotion=PlayerAnimation.locomotion(true,physics.crouched,false,forward,strafe,movedX,movedY,dt,false,false);
                    scriptedPlayerMoving=true;
                    sounds.movement(movedX,movedY,true,physics.crouched,false);
                }
            }else{
                if(physics.support==t.actor){x+=px-t.actor.dx;y+=py-t.actor.dy;z+=pz-t.actor.dz;}
                t.actor.dx=px;t.actor.dy=py;t.actor.dz=pz;
                if("1".equals(t.actor.data.get("npc"))){
                    String sequence=t.actor.data.getOrDefault("zoulu","");
                    if(!sequence.isEmpty()){String[] clip=sequence.split(" ");t.actor.frame=GameEngine.num(clip[(int)(t.time*10)%clip.length],0);}
                }
            }
            if(u>=1){if(t.actor!=null&&"1".equals(t.actor.data.get("npc")))t.actor.frame=0;it.remove();}
        }
        if(game!=null)for(GameEngine.Entity e:game.entities.values())e.visualOpen+=Math.max(-dt*4,Math.min(dt*4,e.open-e.visualOpen));
    }

    @Override public void onSurfaceCreated(GL10 unused,EGLConfig config){
        program=GLES20.glCreateProgram();
        GLES20.glAttachShader(program,shader(GLES20.GL_VERTEX_SHADER,VERTEX));
        GLES20.glAttachShader(program,shader(GLES20.GL_FRAGMENT_SHADER,FRAGMENT));
        GLES20.glLinkProgram(program);
        int[] result=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,result,0);
        if(result[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        mvpLocation=GLES20.glGetUniformLocation(program,"mvp");
        positionLocation=GLES20.glGetAttribLocation(program,"position");
        uvLocation=GLES20.glGetAttribLocation(program,"uv");
        lightUvLocation=GLES20.glGetAttribLocation(program,"luv");
        litLocation=GLES20.glGetUniformLocation(program,"lit");
        softLightLocation=GLES20.glGetUniformLocation(program,"softLight");
        softProgram=GLES20.glCreateProgram();GLES20.glAttachShader(softProgram,shader(GLES20.GL_VERTEX_SHADER,SOFT_VERTEX));GLES20.glAttachShader(softProgram,shader(GLES20.GL_FRAGMENT_SHADER,SOFT_FRAGMENT));GLES20.glLinkProgram(softProgram);GLES20.glGetProgramiv(softProgram,GLES20.GL_LINK_STATUS,result,0);if(result[0]==0)throw new IllegalStateException(GLES20.glGetProgramInfoLog(softProgram));
        softPosition=GLES20.glGetAttribLocation(softProgram,"position");softTexcoord=GLES20.glGetAttribLocation(softProgram,"texcoord");softScene=GLES20.glGetUniformLocation(softProgram,"scene");softTexel=GLES20.glGetUniformLocation(softProgram,"texel");softAmount=GLES20.glGetUniformLocation(softProgram,"amount");
        softQuad=ByteBuffer.allocateDirect(6*4*4).order(ByteOrder.nativeOrder()).asFloatBuffer();softQuad.put(new float[]{-1,-1,0,0,1,-1,1,0,1,1,1,1,-1,-1,0,0,1,1,1,1,-1,1,0,1}).position(0);
        textures.clear();lightmaps.clear();batches.clear();
        white=rgbTexture(new byte[]{-1,-1,-1},1,1);
        pelletMesh=new Batch();pelletMesh.count=360;pelletMesh.diffuse=rgbTexture(new byte[]{110,112,115,-40,-38,-35,-20,-18,-15,80,83,87},2,2);pelletMesh.light=white;
        pelletMesh.vertices=ByteBuffer.allocateDirect(pelletMesh.count*28).order(ByteOrder.nativeOrder()).asFloatBuffer();
        for(int row=0;row<6;row++)for(int column=0;column<10;column++)for(int k:new int[]{0,1,2,0,2,3}){float u=(column+(k==1||k==2?1:0))/10f,v=(row+(k>=2?1:0))/6f;double longitude=u*Math.PI*2,latitude=v*Math.PI;pelletMesh.vertices.put((float)(Math.sin(latitude)*Math.cos(longitude))).put((float)(Math.sin(latitude)*Math.sin(longitude))).put((float)Math.cos(latitude)).put(u).put(v).put(.5f).put(.5f);}pelletMesh.vertices.position(0);
        placeholder=rgbTexture(new byte[]{70,70,75,110,60,110,110,60,110,70,70,75},2,2);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        // Original BSP/SD2 triangles use clockwise fronts.
        GLES20.glFrontFace(GLES20.GL_CW);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glClearColor(0.04f,0.045f,0.055f,1);
        try{
            if(game==null){
                game=new GameEngine(new GameEngine.Source(){
                    public String text(String path)throws Exception{return new String(read("game/scripts/"+path),StandardCharsets.UTF_8);}
                    public List<Map<String,String>> entities(int number)throws Exception{
                        JSONArray rows=new JSONArray(new String(read("game/"+number+".entities.json"),StandardCharsets.UTF_8));
                        ArrayList<Map<String,String>> result=new ArrayList<>();
                        for(int i=0;i<rows.length();i++){JSONObject obj=rows.getJSONObject(i);Map<String,String> map=new LinkedHashMap<>();Iterator<String> keys=obj.keys();while(keys.hasNext()){String key=keys.next();map.put(key,obj.getString(key));}result.add(map);}return result;
                    }
                },this);
            }else pending=level;
        }catch(Exception e){error=e.toString();Log.e("IslandGame","engine init",e);}
        previous=System.nanoTime();
    }
    @Override public void onSurfaceChanged(GL10 unused,int w,int h){
        width=w;height=h;GLES20.glViewport(0,0,w,h);
        if(sceneTexture!=0)GLES20.glDeleteTextures(1,new int[]{sceneTexture},0);if(sceneDepth!=0)GLES20.glDeleteRenderbuffers(1,new int[]{sceneDepth},0);if(sceneFramebuffer!=0)GLES20.glDeleteFramebuffers(1,new int[]{sceneFramebuffer},0);
        int[] limit=new int[1];GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,limit,0);int maxSize=limit[0];GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE,limit,0);maxSize=Math.min(maxSize,limit[0]);float scale=Math.min(1f,(float)maxSize/Math.max(w,h));sceneWidth=Math.max(1,Math.round(w*scale));sceneHeight=Math.max(1,Math.round(h*scale));
        int[] id=new int[1];GLES20.glGenTextures(1,id,0);sceneTexture=id[0];GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,sceneTexture);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,sceneWidth,sceneHeight,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        GLES20.glGenFramebuffers(1,id,0);sceneFramebuffer=id[0];GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,sceneFramebuffer);GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,sceneTexture,0);GLES20.glGenRenderbuffers(1,id,0);sceneDepth=id[0];GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER,sceneDepth);GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER,GLES20.GL_DEPTH_COMPONENT16,sceneWidth,sceneHeight);GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER,GLES20.GL_DEPTH_ATTACHMENT,GLES20.GL_RENDERBUFFER,sceneDepth);int complete=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);if(complete!=GLES20.GL_FRAMEBUFFER_COMPLETE){sceneFramebuffer=0;Log.e("IslandNative","soft render target unavailable: "+complete);}
        Matrix.perspectiveM(projection,0,67,(float)w/h,2,6500);
    }
    @Override public void onDrawFrame(GL10 unused){
        int next;float[] saved;
        synchronized(this){next=pending;saved=restore;pending=0;restore=null;}
        if(next>0){
            try{load(next);error=null;if(saved!=null){x=saved[0];y=saved[1];z=saved[2];yaw=saved[3];pitch=saved[4];}if(afterLoad!=null){x=afterLoad[0];y=afterLoad[1];z=afterLoad[2];yaw=afterLoad[3];pitch=afterLoad[4];afterLoad=null;}game.sceneReady();restorePlayerState();}
            catch(Exception e){error=e.toString();Log.e("IslandNative","load failed",e);}
            previous=System.nanoTime();
        }
        long now=System.nanoTime();float dt=Math.min(0.05f,(now-previous)/1e9f);previous=now;
            if(uiDepth==0){updateTween(dt);if(game!=null)game.tick(dt);update(dt);playerFrame=playerAnimation.frame(dt,locomotion,game!=null&&game.busy()&&!scriptedPlayerMoving);}
        if(sceneFramebuffer!=0){GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,sceneFramebuffer);GLES20.glViewport(0,0,sceneWidth,sceneHeight);}else GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        float a=(float)Math.toRadians(yaw), p=(float)Math.toRadians(pitch), cp=(float)Math.cos(p);
        if(aiming)camera.firstPerson(x,y,z);else camera.update(physics,x,y,z,yaw,pitch,uiDepth==0?dt:0);
        if(cinematic.active&&game!=null&&game.busy())Matrix.setLookAtM(view,0,cinematic.x,cinematic.y,cinematic.z,cinematic.tx,cinematic.ty,cinematic.tz,0,0,1);
        else Matrix.setLookAtM(view,0,camera.x,camera.y,camera.z,camera.x+(float)Math.cos(a)*cp,camera.y+(float)Math.sin(a)*cp,camera.z+(float)Math.sin(p),0,0,1);
        Matrix.multiplyMM(mvp,0,projection,0,view,0);
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(mvpLocation,1,false,mvp,0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"diffuse"),0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"lightmap"),1);
        GLES20.glUniform1f(softLightLocation,softLight);
        GLES20.glEnableVertexAttribArray(positionLocation);GLES20.glEnableVertexAttribArray(uvLocation);GLES20.glEnableVertexAttribArray(lightUvLocation);
        Batch item=null;
        if(game!=null&&held==null&&game.inventory.contains(game.selected)&&(camera.distance>=15||aiming)){try{item=handItem(game.selected);}catch(Exception failure){error=failure.toString();}}
        FloatBuffer playerVertices=player==null?null:player.frames[Math.max(0,Math.min(player.frames.length-1,playerFrame))];
        boolean posing=handWeights!=null&&(weaponTime>=0||aiming);
        if(posing&&player!=null){HandPose.apply(player.frames[physics.crouched?39:0],posedPlayer,handWeights,weaponTime>=0?weaponRanged:true,weaponTime);playerVertices=posedPlayer;}
        if(aiming&&slingRubber!=null)updateRubber(HandPose.draw(weaponTime));
        float[] grip=playerVertices==null||handMarkers==null?null:HandPose.grip(playerVertices,handMarkers);
        for(int bi=0;bi<batches.size()+3;bi++){
            boolean isPlayer=bi==batches.size(),isHand=bi==batches.size()+1,isRubber=bi==batches.size()+2;Batch b=isRubber?(aiming?slingRubber:null):isHand?item:isPlayer?player:batches.get(bi);
            if(isPlayer){drawPellets();if(aiming)GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);}
            if(b==null||(isPlayer&&camera.distance<15))continue;
            GameEngine.Entity entity=game==null?null:game.entities.get(b.owner);
            if(entity!=null&&entity.removed)continue;
            int diffuse = b.diffuse;
            Matrix.setIdentityM(modelMatrix,0);
            if(isPlayer||isHand||isRubber){
                Matrix.translateM(modelMatrix,0,x,y,aiming?z:z-(physics.crouched?36:60));
                Matrix.rotateM(modelMatrix,0,yaw-90,0,0,1);
                if(aiming){Matrix.rotateM(modelMatrix,0,pitch,1,0,0);Matrix.translateM(modelMatrix,0,0,35,-22);}
                if(isPlayer)b.vertices=playerVertices;
                else if(isHand&&grip!=null){if(!aiming)Matrix.translateM(modelMatrix,0,grip[0],grip[1],grip[2]);if(game.selected==20030){if(weaponTime>=0)Matrix.rotateM(modelMatrix,0,HandPose.pickAngle(weaponTime),1,0,0);Matrix.rotateM(modelMatrix,0,90,0,1,0);}else if(game.selected==20100)Matrix.rotateM(modelMatrix,0,-90,1,0,0);}
            }
            if(entity!=null){
                Matrix.translateM(modelMatrix,0,entity.dx,entity.dy,entity.dz);
                if(entity.rotation!=0||entity.rotationX!=0||entity.rotationY!=0){
                    Matrix.translateM(modelMatrix,0,entity.x,entity.y,entity.z);
                    Matrix.rotateM(modelMatrix,0,entity.rotation,0,0,1);Matrix.rotateM(modelMatrix,0,entity.rotationY,0,1,0);Matrix.rotateM(modelMatrix,0,entity.rotationX,1,0,0);
                    Matrix.translateM(modelMatrix,0,-entity.x,-entity.y,-entity.z);
                }
                int frame=Math.max(entity.frame,Math.round(entity.visualOpen*(b.frames.length-1)));b.vertices=b.frames[Math.max(0,Math.min(b.frames.length-1,frame))];
                if (entity.skin >= 0 && entity.skin < entity.skins.length) {
                    String override = entity.skins[entity.skin];
                    if (!override.isEmpty()) {
                        try { diffuse = texture(override); }
                        catch (Exception failure) { error = failure.toString(); }
                    }
                }
                String animated=entity.data.getOrDefault("animatedSkins","");
                String override="";
                if(entity.changeSkin>=0&&entity.changeSkin<changeTextures.length)override=changeTextures[entity.changeSkin];
                else if(!animated.isEmpty()){
                    if(uiDepth==0&&!entity.frozen)entity.textureClock+=dt;
                    String[] cycle=animated.split(",");override=cycle[(int)(entity.textureClock*10)%cycle.length];
                }
                if(!override.isEmpty())try{diffuse=texture(override);}catch(Exception failure){error=failure.toString();}
            }
            Matrix.multiplyMM(drawMatrix,0,mvp,0,modelMatrix,0);GLES20.glUniformMatrix4fv(mvpLocation,1,false,drawMatrix,0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,diffuse);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,b.light);
            GLES20.glUniform1f(litLocation,b.baked?1:0);
            b.vertices.position(0);GLES20.glVertexAttribPointer(positionLocation,3,GLES20.GL_FLOAT,false,28,b.vertices);
            b.vertices.position(3);GLES20.glVertexAttribPointer(uvLocation,2,GLES20.GL_FLOAT,false,28,b.vertices);
            b.vertices.position(5);GLES20.glVertexAttribPointer(lightUvLocation,2,GLES20.GL_FLOAT,false,28,b.vertices);
            if(isRubber)GLES20.glDisable(GLES20.GL_CULL_FACE);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,b.count);
            if(isRubber)GLES20.glEnable(GLES20.GL_CULL_FACE);
        }
        if(sceneFramebuffer!=0){GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);GLES20.glViewport(0,0,width,height);GLES20.glDisable(GLES20.GL_DEPTH_TEST);GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glUseProgram(softProgram);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,sceneTexture);GLES20.glUniform1i(softScene,0);GLES20.glUniform2f(softTexel,8.0f/sceneWidth,8.0f/sceneHeight);GLES20.glUniform1f(softAmount,Math.max(0f,Math.min(1f,softLight))*.90f);softQuad.position(0);GLES20.glEnableVertexAttribArray(softPosition);GLES20.glVertexAttribPointer(softPosition,2,GLES20.GL_FLOAT,false,16,softQuad);softQuad.position(2);GLES20.glEnableVertexAttribArray(softTexcoord);GLES20.glVertexAttribPointer(softTexcoord,2,GLES20.GL_FLOAT,false,16,softQuad);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,6);GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glEnable(GLES20.GL_CULL_FACE);}
        frames++;
        if(now-statusTime>1_000_000_000L){
            float fps=statusTime==0?0:frames*1e9f/(now-statusTime);frames=0;statusTime=now;
            status=error!=null?"加载失败："+error:String.format(Locale.ROOT,"生命 %d%%%s · 弹丸 %d\n%s\n%s",game.health*100/GameEngine.MAX_HEALTH,game.poisoned?"（中毒）":"",game.ammo,game.selected<0?"空手":game.itemName(game.selected),game.error.isEmpty()?interaction:game.error);
            Log.i("IslandNative",String.format(Locale.ROOT,"state level=%d xyz=%.2f,%.2f,%.2f yaw=%.2f pitch=%.2f viewport=%dx%d batches=%d fps=%.1f error=%s",level,x,y,z,yaw,pitch,width,height,batches.size(),fps,error));
            Log.i("IslandNative",String.format(Locale.ROOT,"thirdperson camera=%.2f,%.2f,%.2f distance=%.2f playerFrame=%d locomotion=%d heldItem=%d weaponTime=%.2f aiming=%s cinematic=%s cinematicPosition=%.2f,%.2f,%.2f",camera.x,camera.y,camera.z,camera.distance,playerFrame,locomotion,item==null?-1:game.selected,weaponTime,aiming,cinematic.active,cinematic.x,cinematic.y,cinematic.z));
            if(game!=null)Log.i("IslandGame","state busy="+game.busy()+" target="+targetId+" inventory="+game.inventory+" dialog="+!game.dialog.isEmpty()+" fault="+game.error+" picture="+game.picture+" movie="+game.movie);
            if(game!=null){StringBuilder platforms=new StringBuilder();for(GameEngine.Entity e:game.entities.values())if(!e.removed&&e.solid&&e.ai!=0&&e.distance(x,y,z)<700)platforms.append(e.id).append(':').append(e.x+e.dx).append(',').append(e.y+e.dy).append(',').append(e.z+e.dz).append(';');Log.i("IslandGame","platforms="+platforms+" support="+(physics.support==null?0:physics.support.id));}
            if(game!=null){StringBuilder enemies=new StringBuilder();for(GameEngine.Entity e:game.entities.values())if(!e.removed&&e.enemyAwake)enemies.append(e.id).append(':').append(e.x+e.dx).append(',').append(e.y+e.dy).append(',').append(e.z+e.dz).append(';');Log.i("IslandGame","combat health="+game.health+" ammo="+game.ammo+" selected="+game.selected+" enemies="+enemies);}
        }
        if(game!=null){dialogue=game.dialog;puzzleId=game.puzzle;movieId=game.movie;pictureId=game.picture;scriptError=game.error;}
    }
    private void updateRubber(float draw){
        FloatBuffer v=slingRubber.vertices;v.position(0);float depth=-1.2f-12*draw,pouchZ=9;
        rubberQuad(v,new float[][]{{-6.4f,0,10.65f},{-.65f,depth,pouchZ+.3f},{-.65f,depth,pouchZ-.3f},{-6.4f,0,10.15f}});
        rubberQuad(v,new float[][]{{4,0,10.65f},{.65f,depth,pouchZ+.3f},{.65f,depth,pouchZ-.3f},{4,0,10.15f}});
        rubberQuad(v,new float[][]{{-.9f,depth-.1f,pouchZ+.65f},{.9f,depth-.1f,pouchZ+.65f},{.9f,depth-.1f,pouchZ-.65f},{-.9f,depth-.1f,pouchZ-.65f}});v.position(0);
    }
    private static void rubberQuad(FloatBuffer v,float[][] p){for(int i:new int[]{0,1,2,0,2,3}){for(float value:p[i])v.put(value);v.put(i==1||i==2?1:0).put(i>=2?1:0).put(.5f).put(.5f);}}
    private void drawPellets(){
        if(pelletMesh==null||pellets.isEmpty())return;
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,pelletMesh.diffuse);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,white);GLES20.glUniform1f(litLocation,0);
        for(SlingshotFlight shot:pellets){if(shot.time<0)continue;float u=shot.progress(),px=shot.sx+(shot.tx-shot.sx)*u,py=shot.sy+(shot.ty-shot.sy)*u,pz=shot.sz+(shot.tz-shot.sz)*u;
            int pieces=shot.time>=shot.duration?4:1;
            for(int i=0;i<pieces;i++){float spread=pieces==1?0:(shot.time-shot.duration)*20;
                Matrix.setIdentityM(modelMatrix,0);Matrix.translateM(modelMatrix,0,px+(i%2==0?spread:-spread),py+(i<2?spread:-spread),pz+(i-1.5f)*spread);
                float radius=pieces==1?1.25f:Math.max(.1f,.7f*(1-(shot.time-shot.duration)/.16f));Matrix.scaleM(modelMatrix,0,radius,radius,radius);
                Matrix.multiplyMM(drawMatrix,0,mvp,0,modelMatrix,0);GLES20.glUniformMatrix4fv(mvpLocation,1,false,drawMatrix,0);
                pelletMesh.vertices.position(0);GLES20.glVertexAttribPointer(positionLocation,3,GLES20.GL_FLOAT,false,28,pelletMesh.vertices);pelletMesh.vertices.position(3);GLES20.glVertexAttribPointer(uvLocation,2,GLES20.GL_FLOAT,false,28,pelletMesh.vertices);pelletMesh.vertices.position(5);GLES20.glVertexAttribPointer(lightUvLocation,2,GLES20.GL_FLOAT,false,28,pelletMesh.vertices);GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,pelletMesh.count);
            }
        }
        GLES20.glEnable(GLES20.GL_CULL_FACE);
    }
    private synchronized void update(float dt){
        for(Iterator<SlingshotFlight> it=pellets.iterator();it.hasNext();){SlingshotFlight shot=it.next();float before=shot.time;shot.time+=dt;
            if(before<0&&shot.time>=0&&audio!=null)audio.effect("catapultsend.wav");
            if(shot.impact&&!shot.sounded&&shot.time>=shot.duration){shot.sounded=true;if(audio!=null)audio.effect("bullethit.wav");}
            if(shot.finished())it.remove();}
        attackCooldown=Math.max(0,attackCooldown-dt);
        if(weaponTime>=0){weaponTime+=dt;if(weaponTime>=.55f)weaponTime=-1;}
        if(game==null||game.selected!=20100||!game.inventory.contains(20100))aiming=false;
        if(game==null||game.selected!=20030||!game.inventory.contains(20030))pickMode=false;
        if(game==null)return;
        if(game.busy()){targetId=0;actionHint=game.dialog.isEmpty()?"等待":"确认 / 继续";interaction=game.dialog.isEmpty()?"剧情动作进行中…":"按「确认」继续对话";return;}
        updateEnemies(dt);
        yaw-=controllerYaw*90*dt;pitch=Math.max(-80,Math.min(80,pitch-controllerPitch*70*dt));
        if(keys[0])yaw+=85*dt;if(keys[2])yaw-=85*dt;
        float forward=analogForward+(keys[1]?1:0)-(keys[4]?1:0),strafe=analogRight+(keys[5]?1:0)-(keys[3]?1:0);
        if(jumpForward>0){jumpForward=Math.max(0,jumpForward-dt);if(!keys[4]&&analogForward>=-.1f)forward=1;}
        float norm=(float)Math.sqrt(forward*forward+strafe*strafe);if(norm>1){forward/=norm;strafe/=norm;}
        float a=(float)Math.toRadians(yaw),c=(float)Math.cos(a),s=(float)Math.sin(a);
        float dx=(forward*c+strafe*s)*115*dt,dy=(forward*s-strafe*c)*115*dt;
        if(held!=null){
            float[] b=held.bounds;float cx=(b[0]+b[3])*.5f+held.dx,cy=(b[1]+b[4])*.5f+held.dy,cz=(b[2]+b[5])*.5f+held.dz;
            WorldPhysics.Hit h=physics.trace(cx,cy,cz,cx+dx,cy+dy,cz,(b[3]-b[0])*.5f,(b[4]-b[1])*.5f,(b[5]-b[2])*.5f);
            dx*=h.fraction;dy*=h.fraction;held.dx+=dx;held.dy+=dy;
        }
        boolean wasGrounded=physics.grounded;float fallingSpeed=physics.verticalSpeed;
        float[] position = physics.move(x, y, z, dx, dy, dt);
        if(!wasGrounded&&physics.grounded)sounds.land(fallingSpeed);
        sounds.movement(position[0]-x,position[1]-y,physics.grounded,physics.crouched,held!=null);
        locomotion = PlayerAnimation.locomotion(physics.grounded, physics.crouched, held != null,
            forward, strafe, position[0] - x, position[1] - y, dt, keys[0], keys[2]);
        x = position[0];
        y = position[1];
        z = position[2];
        if(physics.grounded){lastSafeX=x;lastSafeY=y;lastSafeZ=z;}
        if(z<-4000){x=lastSafeX;y=lastSafeY;z=lastSafeZ;physics.verticalSpeed=0;game.say("前方无法通行，已返回最近的落脚点。");}
        targetId=0;float best=Float.MAX_VALUE;
        for(GameEngine.Entity e:game.entities.values()){
            if(!e.removed&&e.ai!=0&&e.auto==1&&!e.triggered&&e.overlapsPlayer(x,y,z,physics.crouched))game.touchAi(e);
            if(e.removed||(game.interaction(e.id)==null&&!"1".equals(e.data.get("pull"))&&!(e.ai!=0&&e.auto>=3)))continue;
            if("1".equals(e.data.get("ifload"))&&covered(e))continue;
            float distance=e.distance(x,y,z-28);
            if(e.virtual&&e.auto==1&&!e.triggered&&e.overlapsPlayer(x,y,z,physics.crouched)){game.interact(e.id,true);if(game.busy())break;}
            if(e.virtual&&e.auto==1)continue;
            if(distance>INTERACTION_REACH)continue;
            float vx=e.x+e.dx-x,vy=e.y+e.dy-y,vz=e.z+e.dz-z,length=(float)Math.sqrt(vx*vx+vy*vy+vz*vz),p=(float)Math.toRadians(pitch),cp=(float)Math.cos(p),sp=(float)Math.sin(p);
            float dot=length<20?1:(vx*c*cp+vy*s*cp+vz*sp)/length;if(dot<.45f)continue;
            if(!physics.visibleTo(x,y,z,e))continue;
            float score=distance+(1-dot)*240;
            if(aimedAt(e,c*cp,s*cp,sp)){
                score-=200;
                // Prefer aimed loose items, including tiny manual BSP pickup
                // volumes, over the furniture hotspot enclosing them.
                if(game.prioritizesAimedPickup(e))score-=75;
            }
            if(score<best){best=score;targetId=e.id;}
        }
        targetId=game.resolveInteraction(targetId);
        GameEngine.Entity actionTarget=game.find(targetId);GameEngine.Rule actionRule=game.interaction(targetId);actionHint=held!=null?"放开箱子":aiming?"弹弓射击":actionTarget!=null&&"1".equals(actionTarget.data.get("pull"))?"抓住箱子":shouldSwingPickaxe(actionTarget)?"攻击":actionRule!=null&&game.selected>=0&&actionRule.n("item",-1)==game.selected?"使用":"调查";
        interaction=held!=null?"正在拖动箱子 · 再按调查放开":targetId==0?"拖动画面转向 · 探索场景寻找线索":"发现可交互物体 · 按「调查 / 使用」";
    }
    private boolean aimedAt(GameEngine.Entity e,float dx,float dy,float dz){
        if(e.bounds==null)return false;float[] b=e.bounds,origin={x,y,z},direction={dx,dy,dz},offset={e.dx,e.dy,e.dz};float enter=0,exit=125;
        for(int i=0;i<3;i++){float lo=b[i]+offset[i]-2,hi=b[i+3]+offset[i]+2;if(Math.abs(direction[i])<.00001f){if(origin[i]<lo||origin[i]>hi)return false;}else{float a=(lo-origin[i])/direction[i],c=(hi-origin[i])/direction[i];enter=Math.max(enter,Math.min(a,c));exit=Math.min(exit,Math.max(a,c));if(enter>exit)return false;}}
        return exit>=0&&enter<=125;
    }
    private void updateEnemies(float dt){
        for(GameEngine.Entity e:game.entities.values()){
            if(e.removed)continue;
            if(!e.data.getOrDefault("model","").toLowerCase(Locale.ROOT).contains("bianfu")){
                int damage=GameEngine.num(e.data.get("hurt"),0);e.attackCooldown=Math.max(0,e.attackCooldown-dt);
                if(damage>0&&e.attackCooldown==0&&e.overlapsPlayer(x,y,z,physics.crouched)){game.hurt(damage);e.attackCooldown=1;}
                continue;
            }
            float vx=x-e.x-e.dx,vy=y-e.y-e.dy,vz=z-10-e.z-e.dz,d=(float)Math.sqrt(vx*vx+vy*vy+vz*vz);
            if(d<280&&physics.visible(x,y,z,e.x+e.dx,e.y+e.dy,e.z+e.dz))e.enemyAwake=true;
            e.enemyClock+=dt;e.attackCooldown=Math.max(0,e.attackCooldown-dt);e.frame=(int)(e.enemyClock*12)%8;
            if(!e.enemyAwake||d<.001f)continue;
            float step=Math.min(d,60*dt),px=e.x+e.dx,py=e.y+e.dy,pz=e.z+e.dz;
            WorldPhysics.Hit h=physics.trace(px,py,pz,px+vx/d*step,py+vy/d*step,pz+vz/d*step,4,4,4);
            e.dx+=vx/d*step*h.fraction;e.dy+=vy/d*step*h.fraction;e.dz+=vz/d*step*h.fraction;
            if(d<32&&e.attackCooldown==0){game.hurt(GameEngine.num(e.data.get("hurt"),10));e.attackCooldown=2;}
        }
    }
    private boolean covered(GameEngine.Entity door){for(GameEngine.Entity e:game.entities.values())if(!e.removed&&e.data.getOrDefault("model","").toLowerCase(Locale.ROOT).contains("qianghui")&&e.distance(door.x,door.y,door.z)<65)return true;return false;}
    private boolean blocked(float px,float py,float pz){
        for(int[] brush:brushes){
            boolean inside=true;
            for(int index:brush){float[] plane=planes[index];if(plane[0]*px+plane[1]*py+plane[2]*pz-plane[3]>9){inside=false;break;}}
            if(inside)return true;
        }
        return false;
    }
    private void load(int number)throws Exception{
        changeTextures=new String(read("game/change-textures.txt"),StandardCharsets.UTF_8).trim().split("\\s+");
        byte[] raw=read("game/"+number+".isg");ByteBuffer input=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if(input.getInt()!=0x324C5349)throw new IllegalArgumentException("Invalid gameplay scene signature");
        x=input.getFloat();y=input.getFloat();z=input.getFloat();yaw=input.getFloat();pitch=-18;
        for(int id:textures.values())GLES20.glDeleteTextures(1,new int[]{id},0);textures.clear();
        for(int id:lightmaps)GLES20.glDeleteTextures(1,new int[]{id},0);lightmaps.clear();batches.clear();
        int count=input.getInt();
        ArrayList<Integer> lightIndexes=new ArrayList<>();
        for(int i=0;i<count;i++){
            int owner=input.getInt();
            int n=input.getInt();byte[] name=new byte[n];input.get(name);String path=new String(name,StandardCharsets.UTF_8);
            int light=input.getInt(),frames=input.getInt(),vertices=input.getInt();
            Batch batch=new Batch();batch.owner=owner;batch.count=vertices;batch.diffuse=texture(path);batch.frames=new FloatBuffer[frames];
            for(int f=0;f<frames;f++){
                ByteBuffer bytes=ByteBuffer.allocateDirect(vertices*28).order(ByteOrder.nativeOrder());
                FloatBuffer values=bytes.asFloatBuffer();for(int j=0;j<vertices*7;j++)values.put(input.getFloat());values.position(0);batch.frames[f]=values;
            }
            batch.vertices=batch.frames[0];lightIndexes.add(light);batches.add(batch);
        }
        int lights=input.getInt();for(int i=0;i<lights;i++){byte[] rgb=new byte[49152];input.get(rgb);lightmaps.add(rgbTexture(rgb,128,128));}
        for(int i=0;i<batches.size();i++){int index=lightIndexes.get(i);Batch b=batches.get(i);b.baked=index>=0&&index<lightmaps.size();b.light=b.baked?lightmaps.get(index):white;}
        int planeCount=input.getInt();planes=new float[planeCount][4];for(float[] plane:planes)for(int i=0;i<4;i++)plane[i]=input.getFloat();
        int brushCount=input.getInt();brushes=new int[brushCount][];for(int i=0;i<brushCount;i++){int sides=input.getInt();brushes[i]=new int[sides];for(int j=0;j<sides;j++)brushes[i][j]=input.getInt();}
        physics.planes=planes;physics.brushes=brushes;physics.verticalSpeed=0;
        physics.objects=game.entities.values();
        loadPlayer();camera.reset();playerAnimation.reset();locomotion=playerFrame=0;
        if(entryId!=0){GameEngine.Entity entry=game.find(entryId);if(entry!=null){x=entry.x;y=entry.y;z=physics.groundEye(x,y,entry.z+36);yaw=90-entryAngle;}}
        lastSafeX=x;lastSafeY=y;lastSafeZ=z;entryId=0;
        if(input.hasRemaining())throw new IllegalArgumentException("Unexpected scene data");
        int triangles=0;for(Batch b:batches)triangles+=b.count/3;
        level=number;Log.i("IslandNative","loaded map="+number+" triangles="+triangles+" brushes="+brushCount);
        if(audio!=null)audio.scene(number);
    }
    private void loadPlayer()throws Exception{
        ByteBuffer input=ByteBuffer.wrap(read("game/player.isp")).order(ByteOrder.LITTLE_ENDIAN);
        if(input.getInt()!=0x31505349)throw new IllegalArgumentException("Invalid player data");
        int nameLength=input.getInt(),frames=input.getInt(),vertices=input.getInt();byte[] name=new byte[nameLength];input.get(name);
        Batch b=new Batch();b.owner=Integer.MIN_VALUE;b.count=vertices;b.diffuse=texture(new String(name,StandardCharsets.UTF_8));b.light=white;b.frames=new FloatBuffer[frames];
        for(int f=0;f<frames;f++){FloatBuffer values=ByteBuffer.allocateDirect(vertices*28).order(ByteOrder.nativeOrder()).asFloatBuffer();for(int j=0;j<vertices*7;j++)values.put(input.getFloat());values.position(0);b.frames[f]=values;}
        int[][] sequences=new int[input.getInt()][];for(int i=0;i<sequences.length;i++){sequences[i]=new int[input.getInt()];for(int j=0;j<sequences[i].length;j++)sequences[i][j]=input.get()&255;}
        if(input.hasRemaining())throw new IllegalArgumentException("Unexpected player data");
        b.vertices=b.frames[0];player=b;playerAnimation.sequences(sequences);
        JSONObject hands=new JSONObject(new String(read("game/hands.json"),StandardCharsets.UTF_8));JSONArray weights=hands.getJSONArray("weights"),markers=hands.getJSONArray("markers");
        handWeights=new float[weights.length()][2];for(int i=0;i<weights.length();i++){handWeights[i][0]=(float)weights.getJSONArray(i).getDouble(0);handWeights[i][1]=(float)weights.getJSONArray(i).getDouble(1);}
        if(handWeights.length!=vertices)throw new IllegalArgumentException("Player hand topology mismatch");
        handMarkers=new int[markers.length()];for(int i=0;i<markers.length();i++)handMarkers[i]=markers.getInt(i);
        JSONObject weapons=hands.getJSONObject("weapons");weaponTextures.put("pick",weapons.getString("pick"));weaponTextures.put("sling",weapons.getString("sling"));
        posedPlayer=ByteBuffer.allocateDirect(vertices*28).order(ByteOrder.nativeOrder()).asFloatBuffer();handItems.clear();
        slingRubber=new Batch();slingRubber.owner=Integer.MIN_VALUE+2;slingRubber.count=18;slingRubber.diffuse=rgbTexture(new byte[]{65,47,30,95,68,41,75,54,33,55,39,25},2,2);slingRubber.light=white;slingRubber.vertices=ByteBuffer.allocateDirect(18*28).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
    private Batch handItem(int id)throws Exception{
        Batch cached=handItems.get(id);if(cached!=null)return cached;
        GameEngine.Item definition=game.items.get(id);if(definition==null)return null;
        String name=id==20030?"held-pick":id==20100?"held-sling":"preview-"+definition.picpos;
        ByteBuffer mesh=ByteBuffer.wrap(read("ui/"+name+".mesh")).order(ByteOrder.LITTLE_ENDIAN);int count=mesh.getInt();
        float[] v=new float[count*5];mesh.asFloatBuffer().get(v);float[] low={Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE},high={-Float.MAX_VALUE,-Float.MAX_VALUE,-Float.MAX_VALUE};
        for(int i=0;i<count;i++)for(int j=0;j<3;j++){low[j]=Math.min(low[j],v[i*5+j]);high[j]=Math.max(high[j],v[i*5+j]);}
        float extent=Math.max(high[0]-low[0],Math.max(high[1]-low[1],high[2]-low[2]));float scale=id==20030?1.3f:id==20100?.8f:(definition.name.contains("剑")?36:18)/Math.max(1,extent);
        Batch result=new Batch();result.owner=Integer.MIN_VALUE+1;result.count=count;result.light=white;result.vertices=ByteBuffer.allocateDirect(count*28).order(ByteOrder.nativeOrder()).asFloatBuffer();
        float[] anchor=id==20030?new float[]{10,-.5f,-1}:id==20100?new float[]{0,5,0}:new float[]{(low[0]+high[0])*.5f,(low[1]+high[1])*.5f,(low[2]+high[2])*.5f};
        for(int i=0;i<count;i++){for(int j=0;j<3;j++)result.vertices.put((v[i*5+j]-anchor[j])*scale);result.vertices.put(v[i*5+3]);result.vertices.put(v[i*5+4]);result.vertices.put(.5f);result.vertices.put(.5f);}result.vertices.position(0);
        result.diffuse=texture(id==20030?weaponTextures.get("pick"):id==20100?weaponTextures.get("sling"):"ui/preview-"+definition.picpos+".png");handItems.put(id,result);return result;
    }
    public String saveGame()throws Exception{
        if(game.busy())throw new IllegalStateException("请结束当前对话或动作后存档");return saveSnapshot();
    }
    public String saveSnapshot()throws Exception{
        java.util.Properties p=new java.util.Properties();p.load(new java.io.StringReader(game.saveSnapshot()));
        p.setProperty("view",x+","+y+","+z+","+yaw+","+pitch);p.setProperty("playerState",physics.verticalSpeed+","+physics.grounded+","+physics.crouched+","+(held==null?-1:held.originalId)+","+aiming+","+jumpForward+","+attackCooldown+","+locomotion);
        StringBuilder flights=new StringBuilder();for(SlingshotFlight shot:pellets)flights.append(shot.snapshot()).append(';');p.setProperty("pellets",flights.toString());
        p.setProperty("weaponState",weaponTime+","+weaponRanged);p.setProperty("pickMode",""+pickMode);p.setProperty("support",""+(physics.support==null?-1:physics.support.originalId));p.setProperty("playerAnimation",playerAnimation.snapshot());java.io.StringWriter out=new java.io.StringWriter();p.store(out,"Island snapshot");return out.toString();
    }
    private void restorePlayerState(){
        java.util.Properties p=afterLoadState;afterLoadState=null;sounds.reset();if(p==null)return;
        String[] a=p.getProperty("playerState","").split(",");if(a.length==8){physics.verticalSpeed=GameEngine.flt(a[0]);physics.grounded=Boolean.parseBoolean(a[1]);physics.crouched=Boolean.parseBoolean(a[2]);held=game.entities.get(GameEngine.num(a[3],-1));physics.held=held;aiming=Boolean.parseBoolean(a[4]);jumpForward=GameEngine.flt(a[5]);attackCooldown=GameEngine.flt(a[6]);locomotion=GameEngine.num(a[7],0);}
        playerAnimation.restore(p.getProperty("playerAnimation",""));lastSafeX=x;lastSafeY=y;lastSafeZ=z;
        pickMode=Boolean.parseBoolean(p.getProperty("pickMode","false"));physics.support=game.entities.get(GameEngine.num(p.getProperty("support"),-1));
        String[] weapon=p.getProperty("weaponState","-1,false").split(",");if(weapon.length==2){weaponTime=GameEngine.flt(weapon[0]);weaponRanged=Boolean.parseBoolean(weapon[1]);}
        pellets.clear();for(String row:p.getProperty("pellets","").split(";")){SlingshotFlight shot=SlingshotFlight.restore(row);if(shot!=null&&!shot.finished())pellets.add(shot);}
    }
    public void loadGame(String text)throws Exception{
        java.util.Properties p=new java.util.Properties();p.load(new java.io.StringReader(text));
        String[] values=p.getProperty("view","").split(",");if(values.length!=5)throw new java.io.IOException("存档视角数据损坏");float[] parsed=new float[5];for(int i=0;i<5;i++){parsed[i]=Float.parseFloat(values[i]);if(!Float.isFinite(parsed[i]))throw new java.io.IOException("存档坐标无效");}game.restore(text);afterLoad=parsed;afterLoadState=p;
    }
    private byte[] read(String path)throws Exception{
        try(InputStream in=assets.open(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}
    }
    private int texture(String path)throws Exception{
        if(path.isEmpty())return placeholder;Integer cached=textures.get(path);if(cached!=null)return cached;
        Bitmap bitmap;try(InputStream input=assets.open(path)){bitmap=BitmapFactory.decodeStream(input);}
        if(bitmap==null)throw new IllegalArgumentException("Cannot decode texture: "+path);
        // Convert the original magenta key before bilinear GPU sampling.
        // Keying the sampled RGB value leaves purple fringes where opaque
        // texels interpolate with the key. Android uploads premultiplied
        // bitmap colors; the shader restores RGB after the alpha cutout.
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        int[] pixels=new int[width*height];
        bitmap.getPixels(pixels,0,width,0,0,width,height);
        boolean keyed=false;
        for(int i=0;i<pixels.length;i++){int c=pixels[i];if(((c>>>16)&255)>244&&(c&255)>244&&((c>>>8)&255)<13){pixels[i]=0;keyed=true;}}
        if(keyed){Bitmap cutout=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);bitmap.recycle();bitmap=cutout;}
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,ids[0]);
        parameters((bitmap.getWidth()&(bitmap.getWidth()-1))==0&&(bitmap.getHeight()&(bitmap.getHeight()-1))==0);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);bitmap.recycle();textures.put(path,ids[0]);return ids[0];
    }
    private int rgbTexture(byte[] rgb,int w,int h){
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,ids[0]);parameters(false);
        ByteBuffer bytes=ByteBuffer.allocateDirect(rgb.length);bytes.put(rgb).position(0);GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGB,w,h,0,GLES20.GL_RGB,GLES20.GL_UNSIGNED_BYTE,bytes);return ids[0];
    }
    private void parameters(boolean repeat){
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,repeat?GLES20.GL_REPEAT:GLES20.GL_CLAMP_TO_EDGE);GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,repeat?GLES20.GL_REPEAT:GLES20.GL_CLAMP_TO_EDGE);
    }
    private int shader(int type,String source){
        int shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);int[] result=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,result,0);
        if(result[0]==0)throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));return shader;
    }
}
