package org.island.nativevalidation;

/** Original CS.SD2 frame sequences. Timing remains subject to original-game comparison. */
public final class PlayerAnimation {
    private int[][] sequences=new int[0][];
    private int action=-1,script=-1,start,end;
    private float clock,scriptLeft;
    public void sequences(int[][] values){sequences=values;reset();}
    public void reset(){action=-1;script=-1;clock=scriptLeft=0;}
    String snapshot(){return action+","+script+","+start+","+end+","+clock+","+scriptLeft;}
    void restore(String state){if(state==null||state.isEmpty())return;String[] a=state.split(",");if(a.length!=6)return;action=Integer.parseInt(a[0]);script=Integer.parseInt(a[1]);start=Integer.parseInt(a[2]);end=Integer.parseInt(a[3]);clock=Float.parseFloat(a[4]);scriptLeft=Float.parseFloat(a[5]);}
    static int locomotion(boolean grounded, boolean crouched, boolean holding,
                          float forward, float strafe, float movedX, float movedY, float dt,
                          boolean turningLeft, boolean turningRight) {
        if (!grounded) return 28;
        if (crouched) return 9;
        // Use post-collision movement so a held stick cannot walk in place against a wall.
        float minTravel = 1.15f * dt;
        if (movedX * movedX + movedY * movedY > minTravel * minTravel) {
            // The original action table has no authored side-step cycles (41/42
            // each contain only frame 0). Keep the character animated while
            // strafing by using the normal locomotion cycle.
            if (Math.abs(strafe) > Math.abs(forward)) return holding ? (forward < 0 ? 17 : 16) : (forward < 0 ? 7 : 3);
            if (holding) return forward < 0 ? 17 : 16;
            return forward < 0 ? 7 : 3;
        }
        if (holding) return 15;
        if (turningLeft) return 10;
        return turningRight ? 11 : 0;
    }
    public float play(int id,int first,int last){
        if(id<0||id>=sequences.length)return 0;
        start=Math.max(0,Math.min(first,sequences[id].length-1));
        end=Math.max(start,Math.min(last,sequences[id].length-1));
        script=id;action=-1;clock=0;scriptLeft=(end-start+1)/10f;
        return scriptLeft;
    }
    public int frame(float dt,int locomotion,boolean busy){
        return frame(dt,locomotion,busy,false);
    }
    public int frame(float dt,int locomotion,boolean busy,boolean holdAction){
        if(script>=0){
            int[] seq=sequences[script];int frame=seq[Math.min(end,start+(int)(clock*10))];
            // Keep an authored pickup/reach pose visible while its original
            // acquisition message is waiting for confirmation.
            if(!holdAction){clock+=dt;scriptLeft-=dt;if(scriptLeft<=0){script=-1;action=-1;}}
            return frame;
        }
        if(sequences.length==0)return 0;
        int next=Math.max(0,Math.min(sequences.length-1,busy?0:locomotion));
        if(action!=next){action=next;clock=0;}
        int[] seq=sequences[action];int step=(int)(clock*10);
        int frame=seq[action==9||action==28||action==15?Math.min(step,seq.length-1):step%seq.length];clock+=dt;return frame;
    }
}
