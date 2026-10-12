#version 430 core
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vCenter;
layout(location = 2) in float vRadius;
layout(location = 3) flat in uint vSeed;
layout(location = 4) flat in vec3 vWorld;
layout(location = 5) flat in vec3 vNormal;
layout(location = 6) flat in float vShade;
layout(location = 7) flat in int vSlot;
layout(location = 0) out vec4 outColor;

#define SAND_TEX_BINDING 2
#include "sand_shade.glsl"

void main() {
    float r2 = dot(vUV, vUV);
    if (r2 > 1.0) {
        discard;
    }
    vec3 pos = vCenter + vec3(vUV, sqrt(1.0 - r2)) * vRadius;
    vec4 clip = G.proj * vec4(pos, 1.0);
    gl_FragDepth = clip.z / clip.w * 0.5 + 0.5; // GL: NDC z [-1,1] -> depth [0,1]
    uint s = vSeed;
    vec3 nWorld = normalize(mat3(G.invView) * vNormal);
    vec3 albedo = sandAlbedo(vSlot, vWorld, nWorld, 0.0) * (0.92 + 0.2 * rand(s));
    outColor = vec4(sandLight(nWorld, albedo, vShade, G.grainA[vSlot].w), 1.0);
}
