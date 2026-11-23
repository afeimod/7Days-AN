package org.island.nativevalidation;
/** Logical three-column inventory movement, shared by keyboard and touch-pad navigation. */
final class ControlIndex {
    static int move(int index,int count,int code){if(count==0)return -1;int delta=code==19?-3:code==20?3:code==21?-1:1;return Math.max(0,Math.min(count-1,index+delta));}
}
