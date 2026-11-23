
ATTR vec3 aPos;
ATTR vec2 aLayerUv;
uniform mat4 uViewProj;
uniform mat4 uView;
VARY vec2 vUv;
VARY float vDepth;
void main()
{
	vUv = aLayerUv;
	vDepth = (uView * vec4(aPos, 1.0)).z;
	gl_Position = uViewProj * vec4(aPos, 1.0);
}
