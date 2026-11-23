package org.island.nativevalidation;

import java.util.*;

/** Touch adaptations of the original LPT puzzle rules. No success shortcut. */
public final class PuzzleState {
    public final int id,type;
    public final int[] board=new int[9],slots;
    public final int[] items,answers;
    public int chosen,moves,progress;
    public String morse="",decoded="";
    public int coveredId;
    private static final String[] CODES={".-","-...","-.-.","-..",".","..-.","--.","....","..",".---","-.-",".-..","--","-.","---",".--.","--.-",".-.","...","-","..-","...-",".--","-..-","-.--","--..","-----",".----","..---","...--","....-",".....","-....","--...","---..","----."};
    private static final String LETTERS="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    public PuzzleState(int id,GameEngine.Rule r){
        this.id=id;type=r.n("type",0);slots=new int[type==104?4:Math.max(0,Math.min(32,r.n("objnum",0)))];
        items=ints(r.fields.get("mouseitem"));answers=ints(r.fields.get("poss"));
        chosen=items.length==0?0:items[0];
        for(int i=0;i<9;i++)board[i]=i;
        if(type==13){
            // Legal moves from the original top-left blank layout guarantee solvability.
            Random random=new Random(13050);int empty=0,previous=-1;
            for(int n=0;n<80;n++){
                ArrayList<Integer> options=new ArrayList<>();for(int i=0;i<9;i++)if(i!=previous&&adjacent(i,empty))options.add(i);
                int p=options.get(random.nextInt(options.size()));board[empty]=board[p];board[p]=0;previous=empty;empty=p;
            }
        }
    }
    private static int[] ints(List<String> rows){if(rows==null)return new int[0];int[] a=new int[rows.size()];for(int i=0;i<a.length;i++)a[i]=GameEngine.num(rows.get(i),0);return a;}
    private static boolean adjacent(int a,int b){return Math.abs(a/3-b/3)+Math.abs(a%3-b%3)==1;}
    public boolean slide(int position){if(type!=13||position<0||position>8)return false;int blank=0;while(board[blank]!=0)blank++;if(!adjacent(position,blank))return false;board[blank]=board[position];board[position]=0;moves++;return true;}
    public void place(int position,Set<Integer> inventory){
        if(position<0||position>=slots.length||!inventory.contains(chosen))return;
        if(slots[position]==chosen){slots[position]=0;return;}
        for(int i=0;i<slots.length;i++)if(slots[i]==chosen)slots[i]=0;
        slots[position]=chosen;moves++;
    }
    public void signal(char ch){if(ch=='.'||ch=='-'){if(morse.length()<5)morse+=ch;}}
    public boolean letter(){for(int i=0;i<CODES.length;i++)if(CODES[i].equals(morse)){decoded+=LETTERS.charAt(i);morse="";return true;}return false;}
    public boolean solved(){
        if(type==13){for(int i=0;i<9;i++)if(board[i]!=i)return false;return true;}
        if(type==101||type==102){
            if(items.length==0||items.length!=answers.length)return false;
            for(int i=0;i<items.length;i++)if(answers[i]<0||answers[i]>=slots.length||slots[answers[i]]!=items[i])return false;
            int count=0;for(int s:slots)if(s!=0)count++;return count==items.length;
        }
        if(type==103||type==201)return progress>=12*360;
        if(type==104){for(int i=0;i<4;i++)if(slots[i]!=i+1)return false;return true;}
        if(type==108)return progress>=80;
        String s=decoded.replace(" ","");
        if(type==205)return s.equals("A")&&morse.isEmpty();
        if(type==305)return s.equals("SOS")&&morse.isEmpty();
        // Recovered from original ITEM 23530: E118, N24.
        if(type==405)return (s.equals("E118N24")||s.equals("11824"))&&morse.isEmpty();
        return false;
    }
    public String encode(){return join(board)+";"+join(slots)+";"+moves+";"+progress+";"+morse+";"+decoded+";"+coveredId;}
    public void decode(String s){
        if(s==null)return;String[] a=s.split(";",-1);if(a.length<7)return;
        fill(board,a[0]);fill(slots,a[1]);moves=GameEngine.num(a[2],0);progress=GameEngine.num(a[3],0);morse=a[4];decoded=a[5];coveredId=GameEngine.num(a[6],0);
    }
    private static String join(int[] a){StringBuilder b=new StringBuilder();for(int n:a){if(b.length()>0)b.append(',');b.append(n);}return b.toString();}
    private static void fill(int[] out,String s){String[] a=s.split(",");for(int i=0;i<out.length&&i<a.length;i++)out[i]=GameEngine.num(a[i],0);}
}
