
ATTR vec3 aPos;
ATTR vec2 aUv0;
ATTR vec2 aUv1;
uniform mat4 uViewProj;
uniform mat4 uView;
VARY HP vec2 vUv0;
VARY HP vec2 vUv1;
VARY float vDepth;
void main()
{
	vUv0 = aUv0;
	vUv1 = aUv1;
	vDepth = (uView * vec4(aPos, 1.0)).z;
	gl_Position = uViewProj * vec4(aPos, 1.0);
}
