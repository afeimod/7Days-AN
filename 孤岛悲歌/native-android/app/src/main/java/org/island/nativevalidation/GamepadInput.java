package org.island.nativevalidation;
import android.view.*;

/** Android gamepad axes, with the device's own dead zone and edge-triggered triggers. */
final class GamepadInput {
    interface Actions {void jump();void crouch();void attack();void investigate();void inventory();void menu();default void sling(){}}
    private final IslandRenderer renderer;private final Actions actions;
    private boolean triggerDown;
    GamepadInput(IslandRenderer renderer,Actions actions){this.renderer=renderer;this.actions=actions;}
    static float deadzone(float value,float flat){flat=Math.max(.12f,Math.min(.5f,flat));return Math.abs(value)<=flat?0:Math.copySign((Math.abs(value)-flat)/(1-flat),value);}
    private float axis(MotionEvent event,int axis){InputDevice device=event.getDevice();InputDevice.MotionRange range=device==null?null:device.getMotionRange(axis,event.getSource());return deadzone(event.getAxisValue(axis),range==null?.15f:range.getFlat());}
    boolean motion(MotionEvent event){
        if((event.getSource()&InputDevice.SOURCE_JOYSTICK)!=InputDevice.SOURCE_JOYSTICK||event.getActionMasked()!=MotionEvent.ACTION_MOVE)return false;
        float forward=-axis(event,MotionEvent.AXIS_Y),right=axis(event,MotionEvent.AXIS_X),hatX=event.getAxisValue(MotionEvent.AXIS_HAT_X),hatY=event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        renderer.move(Math.abs(hatY)>.5f?-hatY:forward,Math.abs(hatX)>.5f?hatX:right);
        InputDevice device=event.getDevice();boolean rightAxes=device==null||device.getMotionRange(MotionEvent.AXIS_Z,event.getSource())!=null;
        renderer.controllerLook(axis(event,rightAxes?MotionEvent.AXIS_Z:MotionEvent.AXIS_RX),axis(event,rightAxes?MotionEvent.AXIS_RZ:MotionEvent.AXIS_RY));
        boolean pressed=Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),event.getAxisValue(MotionEvent.AXIS_BRAKE))>.5f;
        if(pressed&&!triggerDown)actions.attack();triggerDown=pressed;return true;
    }
    boolean key(KeyEvent event){
        boolean down=event.getAction()==KeyEvent.ACTION_DOWN;
        int movement=event.getKeyCode()==KeyEvent.KEYCODE_DPAD_UP?1:event.getKeyCode()==KeyEvent.KEYCODE_DPAD_DOWN?4:event.getKeyCode()==KeyEvent.KEYCODE_DPAD_LEFT?3:event.getKeyCode()==KeyEvent.KEYCODE_DPAD_RIGHT?5:-1;
        if(movement>=0){renderer.key(movement,down);return true;}
        int code=event.getKeyCode();boolean repeat=event.getRepeatCount()>0;
        switch(code){
            case KeyEvent.KEYCODE_BUTTON_A:if(down&&!repeat)actions.jump();return true;
            case KeyEvent.KEYCODE_BUTTON_B:if(down&&!repeat)actions.crouch();return true;
            case KeyEvent.KEYCODE_BUTTON_X:case KeyEvent.KEYCODE_BUTTON_R2:if(down&&!repeat)actions.attack();return true;
            case KeyEvent.KEYCODE_BUTTON_R1:if(down&&!repeat)actions.sling();return true;
            case KeyEvent.KEYCODE_BUTTON_Y:case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:if(down&&!repeat)actions.investigate();return true;
            case KeyEvent.KEYCODE_BUTTON_L1:case KeyEvent.KEYCODE_BUTTON_SELECT:if(down&&!repeat)actions.inventory();return true;
            case KeyEvent.KEYCODE_BUTTON_START:case KeyEvent.KEYCODE_BUTTON_MODE:if(down&&!repeat)actions.menu();return true;
            default:return false;
        }
    }
    void release(){triggerDown=false;renderer.releaseKeys();renderer.controllerLook(0,0);renderer.move(0,0);}
}
