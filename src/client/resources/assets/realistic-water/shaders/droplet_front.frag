#version 430 core
#include "common.glsl"
layout(location = 0) in vec2 vUV;
layout(location = 1) in vec3 vCenter;
layout(location = 2) in float vRadius;
layout(location = 3) in vec4 vData;
layout(location = 0) out vec4 outG;

void main() {
    float r2 = dot(vUV, vUV);
    if (r2 > 1.0) {
        discard;
    }
    vec3 n = vec3(vUV, sqrt(1.0 - r2));
    vec3 pos = vCenter + n * vRadius;
    vec4 clip = G.proj * vec4(pos, 1.0);
    gl_FragDepth = clip.z / clip.w * 0.5 + 0.5; // GL: NDC z [-1,1] -> depth [0,1]
    outG = vec4(octEncode(n), round(vData.y), -pos.z); // vData.y = the particle's mixture code
}
