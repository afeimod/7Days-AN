
ATTR vec3 aPos;
ATTR vec3 aId;
uniform mat4 uViewProj;
uniform mat4 uView;
VARY vec3 vId;   // one id per face: every vertex of a face carries the same value
VARY float vDepth;
void main()
{
	vId = aId;
	vDepth = (uView * vec4(aPos, 1.0)).z;
	gl_Position = uViewProj * vec4(aPos, 1.0);
}
