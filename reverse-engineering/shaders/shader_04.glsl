
ATTR vec3 aPos;
ATTR vec3 aNormal;
ATTR vec2 aUv;      // texels
ATTR float aLight;
ATTR float aBone;   // 0: identity, 1 + n: bone n
uniform mat4 uMVP;      // view-projection * model
uniform vec4 uMVz;      // row 2 of view * model: view z
uniform vec4 uBones[BONE_VECS];   // p * R + T per bone as three columns: x' = (p, 1) . uBones[3b]
uniform int uLit;
uniform vec3 uLightObj; // FUN_000b5490: normalize(-(dir * inverse(world))), object space
uniform vec4 uLightK;   // base, diffuse, min, scale
uniform vec2 uUvScale;  // 1 / texture size
uniform vec2 uUvOffset;
VARY HP vec2 vUv;
VARY float vBright;
VARY float vLevelU;
VARY float vDepth;
void main()
{
	// FUN_000b23e0: only the position goes through the bone matrix
	int b = int(aBone + 0.5) * 3;
	vec4 p = vec4(aPos, 1.0);
	vec4 q = vec4(dot(p, uBones[b]), dot(p, uBones[b + 1]), dot(p, uBones[b + 2]), 1.0);
	if (uLit == 1)
	{
		// FUN_000b4be8: the raw mesh normal against the light in object space; light >= min,
		// times the scale (past 1: shade 0, the brightest level below)
		float l = max(uLightK.x + max(0.0, dot(aNormal, uLightObj)) * uLightK.y, uLightK.z);
		vBright = 31.0 * l / 16.0 * uLightK.w;
	}
	else
	{
		vBright = aLight;
	}
	// shade-table column: shade = level << 8 per vertex, the brightest level 2 (FUN_000ca29c),
	// interpolated; the 32-column table read at level / 32 takes level >> 8 per pixel
	vLevelU = (clamp(31.0 - 16.0 * vBright, 2.0, 31.0) + 0.01) / 32.0;
	vUv = aUv * uUvScale + uUvOffset;
	vDepth = dot(uMVz, q);
	gl_Position = uMVP * q;
}
