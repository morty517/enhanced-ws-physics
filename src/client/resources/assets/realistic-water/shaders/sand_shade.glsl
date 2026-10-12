layout(std430, binding = SAND_TEX_BINDING) readonly buffer SandTex { int sandTex[]; };
// sandTex: per material slot s, sandTex[4s..4s+3] = width, height, average ARGB, offset of its texels; texels are ARGB ints.

vec3 unpackRgb(int argb) {
    return vec3((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF) / 255.0;
}

vec3 sandAverage(int slot) {
    return unpackRgb(sandTex[4 * slot + 2]);
}

vec3 sandTexel(int slot, ivec2 t) {
    ivec2 size = ivec2(sandTex[4 * slot], sandTex[4 * slot + 1]);
    t = ((t % size) + size) % size;
    return unpackRgb(sandTex[sandTex[4 * slot + 3] + t.x + t.y * size.x]);
}

vec3 planarTexel(int slot, vec2 uv, float texelsPerBlock) {
    return sandTexel(slot, ivec2(floor(uv * texelsPerBlock)));
}

vec3 sandAlbedo(int slot, vec3 world, vec3 nWorld, float footprint) {
    float tpb = float(sandTex[4 * slot]);
    vec3 w = pow(abs(nWorld), vec3(8.0));
    w /= w.x + w.y + w.z;
    vec3 c = w.y * planarTexel(slot, vec2(world.x, world.z), tpb)
           + w.x * planarTexel(slot, vec2(world.z, -world.y), tpb)
           + w.z * planarTexel(slot, vec2(world.x, -world.y), tpb);
    float texel = 1.0 / tpb;
    float detail = 1.0 - smoothstep(0.5, 1.5, footprint / texel);
    return mix(sandAverage(slot), c, detail);
}

vec3 sandLight(vec3 nWorld, vec3 albedo, float ao, float brightness) {
    vec3 n2 = nWorld * nWorld;
    float face = n2.x * 0.6 + n2.z * 0.8 + n2.y * (nWorld.y > 0.0 ? 1.0 : 0.5);
    float day = clamp(G.skyColor.w / 0.85, 0.2, 1.0);
    return min(albedo * face * day * ao * brightness, vec3(1.0));
}
