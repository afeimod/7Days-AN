
ATTR vec3 aPos;
ATTR vec2 aUv;
ATTR vec4 aColor;
uniform mat4 uProj;
VARY HP vec2 vUv;
VARY vec4 vColor;
void main()
{
	vUv = aUv;
	vColor = aColor;
	gl_Position = uProj * vec4(aPos, 1.0);
}
