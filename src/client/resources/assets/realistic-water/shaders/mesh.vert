#version 430 core
#include "common.glsl"
layout(std430, binding = 1) readonly buffer Verts { vec4 verts[]; };
layout(std430, binding = 2) readonly buffer Quads { uvec4 quads[]; };
layout(location = 0) out vec3 vView;
layout(location = 1) out vec3 vNormal;
layout(location = 2) out float vWall;
layout(location = 3) out vec4 vMix;
#ifdef MIX_BINDING
// Mixture code per mesh vertex, written by nets_vertices.comp.
layout(std430, binding = MIX_BINDING) readonly buffer VertMix { float vertMix[]; };
#endif

const int CORNER[6] = int[](0, 1, 2, 0, 2, 3);

void main() {
    uvec4 q = quads[gl_VertexID / 6];
    uint vid = q[CORNER[gl_VertexID % 6]];
    vec4 v = verts[vid];
    bool wall;
    vec3 n = unpackNormal(floatBitsToUint(v.w), wall);
    vec4 view = G.view * vec4(G.domainOrigin.xyz + v.xyz * G.domainOrigin.w, 1.0);
    vView = view.xyz;
    vNormal = mat3(G.view) * n;
    vWall = wall ? 1.0 : 0.0;
#ifdef MIX_BINDING
    vMix = mixWeights(vertMix[vid]);
#else
    vMix = vec4(1.0, 0.0, 0.0, 0.0);
#endif
    gl_Position = G.proj * view;
}
