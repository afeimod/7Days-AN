
ATTR vec3 aPos;
ATTR vec2 aUv;
VARY HP vec2 vUv;
void main()
{
	vUv = aUv;
	gl_Position = vec4(aPos.xy, 0.0, 1.0);
}
