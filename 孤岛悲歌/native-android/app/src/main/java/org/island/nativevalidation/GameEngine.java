package org.island.nativevalidation;

import java.util.*;
import java.io.*;

/** Original text-script interpreter; never executes Symbian binaries. */
public final class GameEngine {
    public interface Source { String text(String name) throws Exception; List<Map<String,String>> entities(int level) throws Exception; }
    public interface Host {
        void scene(int map,int entry,float angle);
        float motion(Entity actor,int actorId,String command,String[] args);
        void effect(String command,String[] args);
        void report(String message);
        default String motionState(){return "";}
        default void restoreMotionState(String state){}
    }
    public static final class Entity {
        public int id,originalId,ai,auto,frame,skin,hits;
        public int changeSkin=-1;
        public float textureClock;
        public float rotationX,rotationY;
        public float x,y,z,dx,dy,dz,rotation,open,visualOpen;
        public String[] skins;
        public float[] bounds;
        public float[] floorTriangles;
        public boolean removed,active,used,triggered,solid,virtual,furniture,door,frozen,reverse,enemyAwake;
        public float enemyClock,attackCooldown;
        public final Map<String,String> data;
        Entity(Map<String,String> data) {
            this.data=data;id=originalId=num(data.get("id"),0);ai=num(data.get("ai"),0);
            virtual="virobject".equals(data.get("classname"));
            auto=num(data.get("auto"),virtual?1:0);active=!"0".equals(data.get("active"));
            solid=virtual?"1".equals(data.get("block")):!"0".equals(data.get("block"));
            String model=data.getOrDefault("model","").toLowerCase(Locale.ROOT);
            door=model.contains("men");
            furniture="1".equals(data.get("block"))||model.matches(".*(xiang|gui|men|zhuo|huajia|shizhu|langan|shitou|mutou|shujia|mugang|migang|jiutong|tielong|shidiao|muqiao|shuzhishiban|tanta|jiguanshizhu).*")
                ||(model.contains("cilunzhijia")&&"1".equals(data.get("block")));
            skin=num(data.get("texture"),0);skins=data.getOrDefault("skins","").split(",");
            String[] pos=data.getOrDefault("origin","0 0 0").split("\\s+");x=flt(pos[0]);y=flt(pos[1]);z=flt(pos[2]);
            if(data.containsKey("bounds")){String[] v=data.get("bounds").split("\\s+");bounds=new float[6];for(int i=0;i<6;i++)bounds[i]=flt(v[i]);}
            if(!data.getOrDefault("floorTriangles","").isEmpty()){String[] v=data.get("floorTriangles").split("\\s+");floorTriangles=new float[v.length];for(int i=0;i<v.length;i++)floorTriangles[i]=flt(v[i]);}
        }
        public float distance(float px,float py,float pz){
            if(bounds==null)return (float)Math.sqrt((x+dx-px)*(x+dx-px)+(y+dy-py)*(y+dy-py)+(z+dz-pz)*(z+dz-pz));
            float ax=Math.max(Math.max(bounds[0]+dx-px,0),px-bounds[3]-dx);
            float ay=Math.max(Math.max(bounds[1]+dy-py,0),py-bounds[4]-dy);
            float az=Math.max(Math.max(bounds[2]+dz-pz,0),pz-bounds[5]-dz);
            return (float)Math.sqrt(ax*ax+ay*ay+az*az);
        }
        public boolean overlapsPlayer(float px,float py,float eye,boolean crouched){
            if(bounds==null)return distance(px,py,eye-28)<12;
            float feet=eye-(crouched?36:60);
            return px+10>=bounds[0]+dx&&px-10<=bounds[3]+dx
                &&py+10>=bounds[1]+dy&&py-10<=bounds[4]+dy
                &&eye-4>=bounds[2]+dz&&feet<=bounds[5]+dz;
        }
    }
    public static final class Op {public final String name,value;Op(String n,String v){name=n.toLowerCase(Locale.ROOT);value=v;} public String[] args(){return value.isEmpty()?new String[0]:value.split("\\s+");}}
    public static final class Frame {List<Op> ops;int pc,actor;Frame(List<Op> ops,int actor){this.ops=ops;this.actor=actor;}}
    public static final class Rule {
        public final LinkedHashMap<String,List<String>> fields=new LinkedHashMap<>();
        public int n(String key,int def){return num(s(key),def);}
        public String s(String key){List<String> v=fields.get(key.toLowerCase(Locale.ROOT));return v==null||v.isEmpty()?"":v.get(0);}
    }
    public static final class Item {public int id;public String name,info,description,content;public int tab,required,picpos;Item(int id,Rule r){this.id=id;name=r.s("name");info=r.s("info");description=r.s("desc");content=r.s("content");tab=r.n("tab",1);picpos=r.n("picpos",0);required=r.n("has",-1);}}
    public final LinkedHashMap<Integer,Entity> entities=new LinkedHashMap<>();
    public final LinkedHashMap<Integer,Item> items=new LinkedHashMap<>();
    public final LinkedHashSet<Integer> inventory=new LinkedHashSet<>();
    public final Set<String> removed=new HashSet<>(),used=new HashSet<>(),triggered=new HashSet<>();
    public final List<String> faults=new ArrayList<>();
    public static final int MAX_HEALTH=500;
    public int level=1,selected=-1,ammo=0,health=MAX_HEALTH;
    public boolean ended,poisoned,rescueChoice;
    private float poisonClock;
    public final Set<Integer> markedBoards=new LinkedHashSet<>();
    public String dialog="",caption="",error="";
    public int puzzle=-1;
    public int movie=-1;
    public int picture=-1;
    public boolean loading;
    private final Source source;private final Host host;
    private Map<Integer,List<Op>> actions=new LinkedHashMap<>(),ai=new LinkedHashMap<>();
    private Map<Integer,Rule> interactions=new LinkedHashMap<>();
    private final Map<Integer,List<Op>> messages=new HashMap<>(),dialogues=new HashMap<>();
    private Deque<Frame> stack=new ArrayDeque<>();
    private static final class Fiber {Deque<Frame> stack=new ArrayDeque<>();float wait;Entity entity;}
    private final Map<Integer,Fiber> fibers=new LinkedHashMap<>();
    private final Deque<String> lines=new ArrayDeque<>();
    private final List<Rule> combines=new ArrayList<>();
    private static final int COMBINATION_PUZZLE=2147483646;
    private Rule pendingCombination;
    private final Map<String,String> worldState=new LinkedHashMap<>();
    private final Map<String,Boolean> visibilityOverrides=new LinkedHashMap<>();
    private final Map<Integer,Integer> remoteEntityMaps=new HashMap<>();
    private final Map<Integer,String> runtimeState=new LinkedHashMap<>();
    private String pendingMotionState="";
    private final Map<String,PuzzleState> puzzleStates=new LinkedHashMap<>();
    private float wait;
    private int executionSteps;
    public GameEngine(Source source,Host host)throws Exception{
        this.source=source;this.host=host;
        for(Map.Entry<Integer,List<Op>> e:parse(source.text("ITEM"),"item").entrySet())items.put(e.getKey(),new Item(e.getKey(),rule(e.getValue())));
        Rule combo=null;for(Op op:statements(source.text("COMBINE"))){if(op.name.equals("type")){combo=new Rule();combines.add(combo);}if(combo!=null)combo.fields.computeIfAbsent(op.name,k->new ArrayList<>()).add(op.value);}
        inventory.add(20000);
    }
    public static List<Op> statements(String text){
        ArrayList<Op> out=new ArrayList<>();text=text.replaceAll("//[^\\r\\n]*","");
        for(String part:text.split(";")){String s=part.trim();if(s.isEmpty())continue;String[] words=s.split("\\s+",2);out.add(new Op(words[0],words.length>1?words[1]:""));}return out;
    }
    public static Map<Integer,List<Op>> parse(String text,String heading){
        LinkedHashMap<Integer,List<Op>> out=new LinkedHashMap<>();List<Op> current=null;
        for(Op op:statements(text)){if(op.name.equals(heading)){current=new ArrayList<>();out.put(num(op.value,0),current);}else if(current!=null)current.add(op);}return out;
    }
    private static Rule rule(List<Op> ops){Rule r=new Rule();for(Op op:ops)r.fields.computeIfAbsent(op.name,k->new ArrayList<>()).add(op.value);return r;}
    private String read(String path){try{return source.text(path);}catch(Exception e){return "";}}
    public void load(int map,int entry,float yaw)throws Exception{
        rememberWorld();
        level=map;loading=true;fibers.clear();entities.clear();messages.clear();dialogues.clear();interactions=new LinkedHashMap<>();
        actions=parse(source.text(map+"/ACTION.SPT"),"act");ai=parse(source.text(map+"/AI.SPT"),"spt");
        messages.putAll(parse(read(map+"/MSG"),"message"));dialogues.putAll(parse(read(map+"/DLG"),"talk"));
        for(Map.Entry<Integer,List<Op>> e:parse(read(map+"/LPT"),"object").entrySet())interactions.put(e.getKey(),rule(e.getValue()));
        for(Map<String,String> data:source.entities(map)){Entity e=new Entity(data);e.removed=removed.contains(key(e.originalId))||"1".equals(data.get("remove"));e.used=used.contains(key(e.originalId));e.triggered=triggered.contains(key(e.originalId));restoreEntity(e,worldState.get(key(e.originalId)));if(visibilityOverrides.containsKey(key(e.originalId)))e.removed=visibilityOverrides.get(key(e.originalId));entities.put(e.originalId,e);}
        linkSharedEntityScripts();
        caption="正在进入场景 "+map;host.scene(map,entry,yaw);restoreRuntime(runtimeState.get(map));
    }
    private void linkSharedEntityScripts(){
        Set<Integer> missing=new LinkedHashSet<>();
        for(Entity e:entities.values())if(e.ai!=0&&!ai.containsKey(e.ai))missing.add(e.ai);
        if(missing.isEmpty())return;
        Map<Integer,List<Op>> candidates=new LinkedHashMap<>();Set<Integer> conflicts=new HashSet<>();
        for(int map=1;map<=18;map++)if(map!=level){
            Map<Integer,List<Op>> library=parse(read(map+"/AI.SPT"),"spt");
            for(int id:missing){List<Op> program=library.get(id);if(program==null)continue;
                List<Op> previous=candidates.get(id);
                if(previous!=null){
                    boolean same=previous.size()==program.size();
                    for(int i=0;same&&i<program.size();i++)same=previous.get(i).name.equals(program.get(i).name)&&previous.get(i).value.equals(program.get(i).value);
                    if(!same)conflicts.add(id);
                }else candidates.put(id,program);
            }
        }
        for(int id:missing)if(candidates.containsKey(id)&&!conflicts.contains(id)){ai.put(id,candidates.get(id));host.report("linked-shared-ai:"+id);}
    }
    public void start()throws Exception{reset();load(1,0,90);pushAction(1);}
    public void reset(){markedBoards.clear();pendingCombination=null;stack.clear();fibers.clear();lines.clear();entities.clear();worldState.clear();visibilityOverrides.clear();runtimeState.clear();pendingMotionState="";puzzleStates.clear();dialog="";error="";faults.clear();removed.clear();used.clear();triggered.clear();inventory.clear();inventory.add(20000);selected=-1;health=MAX_HEALTH;ammo=0;ended=false;poisoned=false;rescueChoice=false;poisonClock=0;wait=0;puzzle=-1;movie=-1;picture=-1;executionSteps=0;}
    public void sceneReady(){host.restoreMotionState(pendingMotionState);pendingMotionState="";loading=false;}
    public boolean busy(){return loading||health<=0||ended||rescueChoice||!dialog.isEmpty()||!stack.isEmpty()||wait>0||puzzle>=0||movie>=0||picture>=0||!error.isEmpty();}
    private boolean paused(){return loading||health<=0||ended||rescueChoice||!dialog.isEmpty()||puzzle>=0||movie>=0||picture>=0||!error.isEmpty();}
    public void hurt(int amount){if(health<=0||ended)return;health=Math.max(0,health-Math.max(0,amount));if(amount>0)sound("hit.wav");host.report("damage:"+amount+" health:"+health);}
    private void sound(String sample){host.effect("gameplay-sound",new String[]{sample});}
    public void useMedicine(int id){
        Item item=items.get(id);if(busy()||item==null||item.tab!=2||!inventory.contains(id))return;
        int kind=item.picpos;
        if(kind==34){if(!poisoned){say("现在没有中毒，无需使用解毒草。");return;}poisoned=false;poisonClock=0;}
        else{if(health>=MAX_HEALTH){say("现在不需要使用急救草药。");return;}health=Math.min(MAX_HEALTH,health+(kind==32?200:500));}
        inventory.remove(id);if(selected==id)selected=-1;sound("daojuchenggong.wav");host.report("medicine:"+id+" health:"+health+" poisoned:"+poisoned);
    }
    public void chooseRescue(boolean saveAda){
        if(!rescueChoice)return;rescueChoice=false;
        if(saveAda&&inventory.remove(22260)){if(selected==22260)selected=-1;stack.clear();wait=0;pushAction(733333401);host.report("rescue:ada");}
    }
    public void advance(){if(!dialog.isEmpty()){dialog=lines.isEmpty()?"":lines.removeFirst();host.report("dialog:"+dialog);}}
    public void say(String text){if(text==null||text.isEmpty())return;String clean=text.replace("||","\n\n").replace("\\n","\n");if(dialog.isEmpty())dialog=clean;else lines.addLast(clean);}
    public String itemName(int id){Item i=items.get(id);if(i==null)return "物品 "+id;String name=i.info.isEmpty()?i.name:i.info.replace("：","");return name+(id==23910?"（一）":id==24410?"（二）":id==27130?"（三）":"");}
    private String key(int id){return level+":"+id;}
    public Entity find(int id){for(Entity e:entities.values())if(e.id==id)return e;return null;}
    public Rule interaction(int id){return interactions.get(id);}
    public boolean prioritizesAimedPickup(Entity e){
        if(e==null||e.used||e.removed)return false;
        Rule rule=interaction(e.id);
        if(rule==null||(rule.n("type",0)!=1&&rule.n("type",0)!=2))return false;
        if(!e.virtual)return true;
        // Some loose items use a tiny BSP interaction volume inside a larger
        // furniture hotspot (map 5 white pearl 11551 inside rack 11511).
        if(e.auto!=0||e.solid||e.bounds==null)return false;
        for(int axis=0;axis<3;axis++)if(e.bounds[axis+3]-e.bounds[axis]>32)return false;
        return true;
    }
    private boolean acceptsSelectedItem(Entity e){
        Rule r=interactions.get(e.id);if(r==null)return false;
        int type=r.n("type",0);
        return (type==11||type==103||type==104||type==107||type==201)
            &&r.n("item",-2)==selected&&!(type==103&&e.used);
    }
    public int resolveInteraction(int pointedId){
        Entity hit=find(pointedId);
        if(hit==null||hit.removed||!hit.virtual||hit.auto!=0||hit.bounds==null
            ||selected<0||!inventory.contains(selected)||acceptsSelectedItem(hit))return pointedId;
        // Original maps can put several item rules on the very same physical
        // hotspot (map 15's two owl rings). Keep the hit volume, but choose the
        // rule for the held item instead of depending on entity export order.
        for(Entity e:entities.values()){
            if(e==hit||e.removed||!e.virtual||e.auto!=0||e.bounds==null||!acceptsSelectedItem(e))continue;
            boolean same=true;float[] a={hit.dx,hit.dy,hit.dz},b={e.dx,e.dy,e.dz};
            for(int i=0;i<6;i++)if(Math.abs(hit.bounds[i]+a[i%3]-e.bounds[i]-b[i%3])>.01f){same=false;break;}
            if(same)return e.id;
        }
        return pointedId;
    }
    public void interact(int id,boolean automatic){
        if(busy())return;if(!automatic)id=resolveInteraction(id);Entity e=find(id);Rule r=interactions.get(id);if(e==null||e.removed||r==null)return;
        if(automatic&&(e.triggered||e.auto==0))return;
        int type=r.n("type",0),action=r.n("hasresult",0);
        if(type==104&&selected==r.n("item",-2)&&!inventory.contains(29003)){say("还需要先把三张儿童蜡笔画拼合成完整的图画。");return;}
        if(type==103&&e.used){pushAction(r.n("observeresult",0));return;}
        if(type==11||type==103||type==104||type==107||type==201){
            if(selected<0)action=r.n("observeresult",action);
            else if(selected!=r.n("item",-2))action=r.n("noresult",0);
        }else if(type==1||type==2){action=e.used?r.n("noresult",0):action;}
        else if(type==101||type==102){List<String> candidates=r.fields.get("mouseitem");boolean allowed=candidates!=null&&candidates.contains(String.valueOf(selected));action=r.n(selected<0?"observeresult":allowed?"hasresult":"noresult",0);}
        else if(type==13||type==108||type==205||type==305||type==405){action=r.n("observeresult",0);}
        else if(type>=100){return;}
        if(automatic){e.triggered=true;triggered.add(key(e.originalId));}
        if(action!=0){if(type==1||type==2){e.used=true;used.add(key(e.originalId));}pushAction(action);host.report("interact:"+id+" action:"+action);}
    }
    public PuzzleState puzzleState(){Rule r=interactions.get(puzzle);if(puzzle==COMBINATION_PUZZLE&&pendingCombination!=null){r=new Rule();r.fields.put("type",Collections.singletonList("201"));}if(r==null)return null;String key=key(puzzle);PuzzleState s=puzzleStates.get(key);if(s==null){s=new PuzzleState(puzzle,r);puzzleStates.put(key,s);}if(inventory.contains(selected))s.chosen=selected;return s;}
    public boolean completePuzzle(boolean success){
        int id=puzzle;Rule r=interactions.get(id);PuzzleState state=puzzleState();
        if(success&&(state==null||!state.solved()))return false;
        if(id==COMBINATION_PUZZLE){
            puzzle=-1;Rule recipe=pendingCombination;pendingCombination=null;
            if(success&&recipe!=null&&inventory.contains(recipe.n("item1",-1))&&inventory.contains(recipe.n("item2",-1))){applyCombination(recipe);puzzleStates.remove(key(id));}
            return true;
        }
        puzzle=-1;if(r!=null){
            if(success&&state!=null){
                Entity object=find(id);if(object!=null){object.used=true;used.add(key(object.originalId));}
                if(state.type==101||state.type==102)for(int item:state.items){inventory.remove(item);if(selected==item)selected=-1;}
                if(state.type==108&&state.coveredId!=0)remove(find(state.coveredId),true);
            }
            pushAction(r.n(success?"overresult":"failresult",0));
        }return true;
    }
    public boolean attack(int id){
        if(selected==20100&&ammo<=0){say("没有弹丸了。");return false;}
        if(busy())return false;Entity e=find(id);if(e==null||e.removed)return false;
        if(e.auto==2&&interactions.containsKey(id)){
            if(selected!=20100)return false;
            if(ammo<=0){say("没有弹丸了。");return false;}
            ammo--;interact(id,false);host.report("ranged-switch:"+id);return true;
        }
        if(e.ai==0||!ai.containsKey(e.ai))return false;
        if(selected==20100){if(ammo<=0){say("没有弹丸了。");return false;}ammo--;}
        else if(selected!=20030||!inventory.contains(20030))return false;
        if(e.auto==3&&e.triggered){host.report("repeat-hit-ignored:"+e.id);return false;}
        e.hits++;int resistance=num(e.data.get("break"),1);if(e.hits<resistance){host.report("hit:"+e.id+" count:"+e.hits);return true;}
        if(e.auto==3){e.triggered=true;triggered.add(key(e.originalId));}
        if(e.data.getOrDefault("model","").toLowerCase(Locale.ROOT).contains("bianfu"))remove(e,true);
        stack.push(new Frame(ai.get(e.ai),e.id));host.report("attack:"+e.id+" ai:"+e.ai);return true;
    }
    public boolean fireSlingshot(){if(busy()||selected!=20100||!inventory.contains(20100))return false;if(ammo<=0){say("没有弹丸了。");return false;}ammo--;return true;}
    public boolean pushAction(int id){List<Op> program=actions.get(id);if(program==null){if(id!=0)fail("缺少原动作脚本 "+level+":"+id);return false;}stack.push(new Frame(program,0));return true;}
    private void sayTextGroup(Map<Integer,List<Op>> groups,int id){List<Op> group=groups.get(id);if(group==null)return;for(Op op:group){if(op.name.equals("say")){String[] s=op.value.split("\\s+",2);say(s.length>1?s[1]:s[0]);}else if(op.name.equals("content"))say(op.value);}}
    private void fail(String detail){error=detail;faults.add(detail);host.report("FAULT:"+detail);}
    private void remove(Entity e,boolean value){if(e==null)return;e.removed=value;visibilityOverrides.put(key(e.originalId),value);if(value)removed.add(key(e.originalId));else removed.remove(key(e.originalId));}
    private void removeById(int id,boolean value){
        Entity e=find(id);if(e!=null){remove(e,value);return;}if(id<=3)return;
        Integer map=remoteEntityMaps.get(id);
        if(map==null){
            int candidate=0;
            for(int n=1;n<=18;n++)if(n!=level)try{
                for(Map<String,String> row:source.entities(n))if(num(row.get("id"),0)==id){candidate=candidate==0?n:-1;break;}
            }catch(Exception failure){host.report("remote-entity-read:"+n+":"+failure.getMessage());}
            map=candidate;remoteEntityMaps.put(id,map);
        }
        if(map>0){String address=map+":"+id;visibilityOverrides.put(address,value);if(value)removed.add(address);else removed.remove(address);host.report("remote-visibility:"+address+":"+value);}
    }
    public void startAi(Entity e){
        if(e==null||e.removed||e.ai==0||fibers.containsKey(e.originalId))return;List<Op> ops=ai.get(e.ai);if(ops==null)return;
        Fiber f=new Fiber();f.entity=e;f.stack.push(new Frame(ops,e.id));fibers.put(e.originalId,f);e.active=true;
    }
    public void touchAi(Entity e){if(e==null||e.triggered)return;e.triggered=true;triggered.add(key(e.originalId));startAi(e);}
    public void tick(float dt){
        if(poisoned&&!paused()&&stack.isEmpty()&&wait<=0){
            // One point per original update; native adaptation uses a fixed 10 Hz clock.
            poisonClock+=Math.max(0,dt);int points=(int)((poisonClock+.00001f)*10);
            if(points>0){poisonClock=Math.max(0,poisonClock-points*.1f);health=Math.max(0,health-points);}
        }
        executionSteps=0;runProgram(dt);
        if(paused())return;
        for(Entity e:entities.values())if(e.active&&e.ai!=0&&e.auto==0&&!fibers.containsKey(e.originalId))startAi(e);
        Deque<Frame> main=stack;float mainWait=wait;
        try{for(Fiber f:new ArrayList<>(fibers.values())){
            if(f.entity.removed){fibers.remove(f.entity.originalId);continue;}
            if(f.entity.frozen)continue;
            stack=f.stack;wait=f.wait;runProgram(dt);f.wait=wait;
            if(stack.isEmpty()){f.entity.active=false;fibers.remove(f.entity.originalId);}
            if(paused())break;
        }}finally{stack=main;wait=mainWait;}
    }
    private void runProgram(float dt){
        if(paused())return;
        if(wait>0){wait-=dt;return;}
        for(int budget=0;budget<96&&!stack.isEmpty()&&wait<=0&&!paused();budget++){
            Frame frame=stack.peek();if(frame.pc>=frame.ops.size()){stack.pop();continue;}
            if(++executionSteps>200000){fail("脚本超出执行上限，已停止以保留状态");return;}
            Op op=frame.ops.get(frame.pc++);String[] a=op.args();int n=a.length>0?num(a[0],0):0;
            Entity actor=find(frame.actor);
            switch(op.name){
                case "layer":break;
                case "talk":sayTextGroup(dialogues,n);break;
                case "info":sayTextGroup(messages,n);break;
                case "get":if(inventory.add(n))sound("daojuhuode.wav");host.report("get:"+n);break;
                case "getball":ammo+=n;break;
                case "removeitem":inventory.remove(n);if(selected==n)selected=-1;break;
                case "sleep":wait=n/10f;break;
                case "goscript":if(actions.containsKey(n)){frame.ops=actions.get(n);frame.pc=0;}else fail("缺少跳转动作 "+n);break;
                case "call":if(a.length>1){int script=num(a[1],0);List<Op> p=ai.get(script);if(p!=null)stack.push(new Frame(p,n));else fail("缺少 AI 脚本 "+script);}break;
                case "goto":frame.pc=Math.max(0,Math.min(n,frame.ops.size()));wait=.05f;break;
                case "remove":case "unremove":for(String value:a)removeById(num(value,-1),op.name.equals("remove"));break;
                case "disappear":remove(find(n),true);break;
                case "active":for(String value:a){int id=num(value,-1);Entity e=id==-2?actor:find(id);if(e!=null){e.frozen=false;e.active=true;fibers.remove(e.originalId);startAi(e);host.effect("active",new String[]{""+e.id});}}break;
                case "open":case "close":{Entity e=find(n);if(e!=null)e.open=op.name.equals("open")?1:0;wait=.25f;break;}
                case "change":{Entity e=find(n);if(e!=null&&a.length>1)e.id=num(a[1],e.id);break;}
                case "loadmap":try{load(n,a.length>1?num(a[1],0):0,a.length>2?flt(a[2]):0);}catch(Exception e){fail(e.toString());}break;
                case "move":case "moveto":case "toward":case "rotate":case "round":wait=host.motion(actor,frame.actor,op.name,a);break;
                case "setstill":break;
                case "freeze":for(String value:a){int id=num(value,-1);Entity e=id==-2?actor:find(id);if(e!=null){e.frozen=true;e.active=false;fibers.remove(e.originalId);if(e==actor){stack.clear();wait=0;}}}host.effect(op.name,a);break;
                case "oppose":for(String value:a){int id=num(value,-1);Entity e=id==-2?actor:find(id);if(e!=null){e.active=!e.active;e.frozen=!e.active;if(e.active)startAi(e);}}host.effect(op.name,a);if(actor!=null&&!actor.active)return;break;
                case "cgtexture":if(actor!=null)actor.changeSkin=n;wait=.1f;break;
                case "setflags":{
                    markedBoards.add(n);
                    if(markedBoards.size()>=15&&!inventory.contains(296001)){
                        for(int id=296001;id<=296004;id++)if(items.containsKey(id))inventory.add(id);
                        say("恭喜！十五块木板全部标记！所有故事纸片全激活！请进入道具栏查看！");
                    }break;
                }
                case "play":{Rule r=interactions.get(n);if(r!=null){puzzle=n;PuzzleState state=puzzleState();if(a.length>1)state.coveredId=num(a[1],0);host.report("puzzle:"+n);}else fail("未恢复的 play 对象 "+n);break;}
                case "playavi":case "playaviw":movie=n;host.report("movie:"+n);break;
                case "showpic":picture=n;host.report("picture:"+n);break;
                case "rollpic":picture=1000+n;host.report("credits:"+n);break;
                case "rolltext":sayTextGroup(messages,n);break;
                case "showend":ended=true;host.report("ending:"+level);break;
                case "toxin":poisoned=true;host.report("poison:true");break;
                case "untoxin":poisoned=false;poisonClock=0;host.report("poison:false");break;
                case "saveada":if(inventory.contains(22260)){rescueChoice=true;host.report("rescue:choice");}else say("没有可以救阿大的解毒草。");break;
                case "setaction":wait=host.motion(find(n),n,op.name,a);break;
                case "follow":case "fog":case "setblack":case "allblack":case "fadein":case "fadeout":case "playsound":case "playback":case "bound":host.effect(op.name,a);break;
                default:fail("未实现的原指令 "+op.name+" "+op.value);
            }
        }
    }
    public void combine(int first,int second){
        if(busy()||first==second||!inventory.contains(first)||!inventory.contains(second))return;
        for(Rule r:combines){int a=r.n("item1",0),b=r.n("item2",0);if((a==first&&b==second)||(a==second&&b==first)){
            if(r.n("type",0)==1){pendingCombination=r;puzzle=COMBINATION_PUZZLE;host.report("puzzle:battery-crank");return;}
            applyCombination(r);return;}}
        sound("daojushibai.wav");say("两个道具组合起来使用，没有产生任何效果！");
    }
    private void applyCombination(Rule r){inventory.remove(r.n("item1",-1));inventory.remove(r.n("item2",-1));int c=r.n("item3",-1),d=r.n("item5",-1);if(c>=0)inventory.add(c);if(d>=0)inventory.add(d);selected=-1;sound("daojuchenggong.wav");say(r.s("success")+"\n"+r.s("info"));}
    public String save()throws Exception{
        if(busy())throw new IllegalStateException("请结束当前对话或动作后存档");
        return saveSnapshot();
    }
    public String saveSnapshot()throws Exception{
        if(loading)throw new IllegalStateException("请等待场景加载完成");
        if(movie>=0)throw new IllegalStateException("请等待过场播放完成");
        Properties p=new Properties();p.setProperty("version","4");p.setProperty("poisoned",""+poisoned);p.setProperty("poisonClock",""+poisonClock);p.setProperty("level",""+level);p.setProperty("selected",""+selected);p.setProperty("ammo",""+ammo);p.setProperty("health",""+health);p.setProperty("inventory",join(inventory));p.setProperty("removed",String.join(",",removed));p.setProperty("used",String.join(",",used));p.setProperty("triggered",String.join(",",triggered));
        p.setProperty("dialog",dialog);p.setProperty("caption",caption);p.setProperty("error",error);p.setProperty("wait",""+wait);p.setProperty("puzzleId",""+puzzle);p.setProperty("pictureId",""+picture);p.setProperty("ended",""+ended);p.setProperty("rescueChoice",""+rescueChoice);p.setProperty("executionSteps",""+executionSteps);
        p.setProperty("lineCount",""+lines.size());int lineIndex=0;for(String line:lines)p.setProperty("line."+(lineIndex++),line);
        p.setProperty("mainCount",""+stack.size());int frameIndex=0;for(Frame frame:stack){String k="main."+(frameIndex++)+".";p.setProperty(k+"actor",""+frame.actor);p.setProperty(k+"pc",""+frame.pc);p.setProperty(k+"ops",encodeOps(frame.ops));}
        if(pendingCombination!=null){StringBuilder recipe=new StringBuilder();for(Map.Entry<String,List<String>> e:pendingCombination.fields.entrySet())for(String value:e.getValue())recipe.append(e.getKey()).append(' ').append(value).append(';');p.setProperty("combination",recipe.toString());}
        rememberWorld();for(Map.Entry<String,String> e:worldState.entrySet())p.setProperty("world."+e.getKey(),e.getValue());
        for(Map.Entry<String,Boolean> e:visibilityOverrides.entrySet())p.setProperty("visibility."+e.getKey(),e.getValue().toString());
        for(Map.Entry<Integer,String> e:runtimeState.entrySet())p.setProperty("runtime."+e.getKey(),e.getValue());
        p.setProperty("markedBoards",join(markedBoards));
        for(Map.Entry<String,PuzzleState> e:puzzleStates.entrySet())p.setProperty("puzzle."+e.getKey(),e.getValue().encode());
        StringWriter w=new StringWriter();p.store(w,"Island native game progress");return w.toString();
    }
    public void restore(String text)throws Exception{
        Properties p=new Properties();p.load(new StringReader(text));if(!Arrays.asList("1","2","3","4").contains(p.getProperty("version")))throw new IOException("存档版本不兼容");reset();inventory.clear();
        for(String id:p.getProperty("inventory","").split(","))if(!id.isEmpty())inventory.add(num(id,0));
        restoreSet(removed,p.getProperty("removed",""));restoreSet(used,p.getProperty("used",""));restoreSet(triggered,p.getProperty("triggered",""));
        selected=num(p.getProperty("selected"),-1);ammo=num(p.getProperty("ammo"),0);health=num(p.getProperty("health"),MAX_HEALTH);if("1".equals(p.getProperty("version"))&&health<=100)health*=5;health=Math.min(MAX_HEALTH,health);
        poisoned=Boolean.parseBoolean(p.getProperty("poisoned","false"));poisonClock=flt(p.getProperty("poisonClock","0"));
        if(num(p.getProperty("version"),1)<3&&num(p.getProperty("level"),1)==4&&inventory.contains(21210)&&inventory.contains(21250))poisoned=true;
        for(String id:p.getProperty("markedBoards","").split(","))if(!id.isEmpty())markedBoards.add(num(id,0));
        for(String key:p.stringPropertyNames())if(key.startsWith("world."))worldState.put(key.substring(6),p.getProperty(key));
        for(String key:p.stringPropertyNames())if(key.startsWith("visibility."))visibilityOverrides.put(key.substring(11),Boolean.parseBoolean(p.getProperty(key)));
        for(String key:p.stringPropertyNames())if(key.startsWith("runtime."))runtimeState.put(num(key.substring(8),0),p.getProperty(key));
        load(num(p.getProperty("level"),1),0,90);
        List<Op> entry=actions.get(level);if(entry!=null)for(Op op:entry)if(op.name.equals("playback"))host.effect("playback",op.args());
        for(String key:p.stringPropertyNames())if(key.startsWith("puzzle.")){
            String[] address=key.substring(7).split(":");int map=num(address[0],0),id=num(address[1],0);
            Map<Integer,List<Op>> rows=parse(read(map+"/LPT"),"object");List<Op> ops=rows.get(id);if(id==COMBINATION_PUZZLE)ops=statements("type 201;");
            if(ops!=null){PuzzleState s=new PuzzleState(id,rule(ops));s.decode(p.getProperty(key));puzzleStates.put(key.substring(7),s);}
        }
        if("4".equals(p.getProperty("version"))){
            dialog=p.getProperty("dialog","");caption=p.getProperty("caption","");error=p.getProperty("error","");wait=flt(p.getProperty("wait","0"));puzzle=num(p.getProperty("puzzleId"),-1);picture=num(p.getProperty("pictureId"),-1);ended=Boolean.parseBoolean(p.getProperty("ended","false"));rescueChoice=Boolean.parseBoolean(p.getProperty("rescueChoice","false"));executionSteps=num(p.getProperty("executionSteps"),0);
            for(int i=0;i<num(p.getProperty("lineCount"),0);i++)lines.addLast(p.getProperty("line."+i,""));
            for(int i=0;i<num(p.getProperty("mainCount"),0);i++){String k="main."+i+".";Frame frame=new Frame(statements(p.getProperty(k+"ops","")),num(p.getProperty(k+"actor"),0));frame.pc=num(p.getProperty(k+"pc"),0);stack.addLast(frame);}
            if(p.containsKey("combination"))pendingCombination=rule(statements(p.getProperty("combination")));
        }
    }
    private static String encodeOps(List<Op> ops){StringBuilder s=new StringBuilder();for(Op op:ops)s.append(op.name).append(' ').append(op.value).append(';');return s.toString();}
    private void rememberWorld(){
        if(entities.isEmpty()||loading)return;
        for(Entity e:entities.values())worldState.put(key(e.originalId),e.id+","+e.dx+","+e.dy+","+e.dz+","+e.rotation+","+e.open+","+e.frame+","+e.active+","+e.skin+","+e.hits+","+e.frozen+","+e.reverse+","+e.removed+","+e.changeSkin+","+e.rotationX+","+e.rotationY+","+e.visualOpen+","+e.textureClock+","+e.enemyAwake+","+e.enemyClock+","+e.attackCooldown);
        try{
            Properties p=new Properties();p.setProperty("motions",host.motionState());
            for(Fiber f:fibers.values()){
                String prefix="fiber."+f.entity.originalId+".";p.setProperty(prefix+"wait",""+f.wait);int i=0;
                for(Frame frame:f.stack){String k=prefix+(i++)+".";p.setProperty(k+"actor",""+frame.actor);p.setProperty(k+"pc",""+frame.pc);StringBuilder s=new StringBuilder();for(Op op:frame.ops)s.append(op.name).append(' ').append(op.value).append(';');p.setProperty(k+"ops",s.toString());}
                p.setProperty(prefix+"count",""+i);
            }
            StringWriter w=new StringWriter();p.store(w,"paused map runtime");runtimeState.put(level,w.toString());
        }catch(IOException e){throw new IllegalStateException(e);}
    }
    private void restoreRuntime(String text)throws IOException{
        pendingMotionState="";if(text==null)return;Properties p=new Properties();p.load(new StringReader(text));pendingMotionState=p.getProperty("motions","");
        for(Entity entity:entities.values()){
            String prefix="fiber."+entity.originalId+".";int count=num(p.getProperty(prefix+"count"),0);if(count<=0)continue;
            Fiber f=new Fiber();f.entity=entity;f.wait=flt(p.getProperty(prefix+"wait","0"));
            for(int i=0;i<count;i++){String k=prefix+i+".";Frame frame=new Frame(statements(p.getProperty(k+"ops","")),num(p.getProperty(k+"actor"),entity.id));frame.pc=num(p.getProperty(k+"pc"),0);f.stack.addLast(frame);}
            fibers.put(entity.originalId,f);
        }
    }
    private void restoreEntity(Entity e,String text){if(text==null)return;String[] a=text.split(",");e.id=num(a[0],e.id);e.dx=flt(a[1]);e.dy=flt(a[2]);e.dz=flt(a[3]);e.rotation=flt(a[4]);e.open=flt(a[5]);e.visualOpen=e.open;e.frame=num(a[6],0);e.active=Boolean.parseBoolean(a[7]);if(a.length>8)e.skin=num(a[8],0);if(a.length>9)e.hits=num(a[9],0);if(a.length>10)e.frozen=Boolean.parseBoolean(a[10]);if(a.length>11)e.reverse=Boolean.parseBoolean(a[11]);if(a.length>12)e.removed=Boolean.parseBoolean(a[12]);if(a.length>13)e.changeSkin=num(a[13],-1);if(a.length>15){e.rotationX=flt(a[14]);e.rotationY=flt(a[15]);}if(a.length>20){e.visualOpen=flt(a[16]);e.textureClock=flt(a[17]);e.enemyAwake=Boolean.parseBoolean(a[18]);e.enemyClock=flt(a[19]);e.attackCooldown=flt(a[20]);}}
    private static void restoreSet(Set<String> out,String text){for(String s:text.split(","))if(!s.isEmpty())out.add(s);}
    private static String join(Collection<Integer> values){StringBuilder b=new StringBuilder();for(int i:values){if(b.length()>0)b.append(',');b.append(i);}return b.toString();}
    public static int num(String text,int fallback){try{return Integer.parseInt(text.trim());}catch(Exception e){return fallback;}}
    public static float flt(String text){try{return Float.parseFloat(text);}catch(Exception e){return 0;}}
}
