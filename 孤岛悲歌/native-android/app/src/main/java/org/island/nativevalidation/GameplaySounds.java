package org.island.nativevalidation;

/** Emit original samples from actual movement, never from a held input alone. */
final class GameplaySounds {
    interface Sink {void play(String sample);}
    private final Sink sink;private float travel,boxTravel;private boolean left;
    GameplaySounds(Sink sink){this.sink=sink;}
    void reset(){travel=boxTravel=0;left=false;}
    void movement(float dx,float dy,boolean grounded,boolean crouched,boolean holding){
        if(!grounded){travel=0;boxTravel=0;return;}
        float distance=(float)Math.hypot(dx,dy);
        if(distance<.001f)return;
        travel+=distance;
        if(travel>=(crouched?48:36)){travel%=crouched?48:36;left=!left;sink.play(left?"footleft.wav":"footright.wav");}
        if(holding){boxTravel+=distance;if(boxTravel>=65){boxTravel%=65;sink.play("xiangzihuadong.wav");}}else boxTravel=0;
    }
    void jump(){travel=0;sink.play("readyjump.wav");}
    void land(float fallingSpeed){if(fallingSpeed< -140)sink.play("hitload.wav");}
    void weapon(boolean ranged,boolean hit){sink.play(ranged?"catapultsend.wav":"pick.wav");if(hit)sink.play(ranged?"bullethit.wav":"pickhit.wav");}
}
