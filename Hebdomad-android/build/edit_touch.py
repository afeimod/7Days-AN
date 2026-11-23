from pathlib import Path
p=Path(__file__).resolve().parents[1]/'java/org/hebdomad/android/TouchControls.java'
s=p.read_text(encoding='utf-8')
s=s.replace('joystick(c);for(Button b:buttons)metalButton(c,b,held.contains(b.scan)&&b.scan!=0);','if(buttonsVisible)joystick(c);for(Button b:buttons)if(buttonsVisible||b.scan==0)metalButton(c,b,held.contains(b.scan)&&b.scan!=0);')
s=s.replace('public boolean onTouchEvent(MotionEvent e){if(editing)', '''public boolean onTouchEvent(MotionEvent e){
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN&&!editing){
            float x=e.getX()/scale,y=e.getY()/scale;boolean hit=buttonsVisible&&Math.hypot(x-jx,y-jy)<110*controlSize;
            for(Button b:buttons)if((buttonsVisible||b.scan==0)&&hits(b,x,y))hit=true;
            if(!hit)return false; // Let SDL receive direct menu taps.
        }
        if(editing)''')
s=s.replace('if(joystick<0&&Math.hypot(x-jx,y-jy)<110*controlSize)','if(buttonsVisible&&joystick<0&&Math.hypot(x-jx,y-jy)<110*controlSize)')
s=s.replace('else for(Button b:buttons)if(hits(b,x,y))','else for(Button b:buttons)if((buttonsVisible||b.scan==0)&&hits(b,x,y))')
s=s.replace('0,0,"画面"','0,0,"设置"')
p.write_text(s,encoding='utf-8')
