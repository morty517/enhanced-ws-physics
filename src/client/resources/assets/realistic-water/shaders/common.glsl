struct Particle {
    vec4 pos;
    vec4 vel;
};

#define GID_U (gl_GlobalInvocationID.x + gl_GlobalInvocationID.y * gl_NumWorkGroups.x * gl_WorkGroupSize.x)
#define GROUP_ID (gl_WorkGroupID.x + gl_WorkGroupID.y * gl_NumWorkGroups.x)

#define MAX_ENTITIES 32
#define MAX_SOURCES 64
#define MAX_REMOVE 16
#define AABB_BIAS 1048576

layout(std140, binding = 0) uniform Globals {
    ivec4 dims;
    ivec4 faces;
    ivec4 caps;
    ivec4 flags;
    vec4 gravityDt;
    vec4 simA;
    vec4 simB;
    vec4 wwA;
    vec4 wwB;
    vec4 wwC;
    vec4 wwD;
    mat4 proj;
    mat4 view;
    mat4 invProj;
    mat4 invView;
    vec4 domainOrigin;
    vec4 screen;
    vec4 sunDir;
    vec4 skyColor;
    vec4 fogColor;
    vec4 waterA;
    vec4 absorb;
    vec4 scatter;
    vec4 foamA;
    vec4 ent[MAX_ENTITIES * 3];
    vec4 sources[MAX_SOURCES];
    vec4 camGrid;
    vec4 misc;
    ivec4 fine;
    vec4 surf;
    ivec4 boxMin;
    ivec4 boxMax;
    ivec4 fineBoxMin;
    ivec4 fineBoxMax;
    ivec4 removeInfo;
    vec4 removeBox[MAX_REMOVE * 2];
    vec4 killInfo;
    vec4 shadeA;
    vec4 shadeB;
    vec4 matA;
    vec4 matB;
    vec4 matC;
    vec4 matD;
    vec4 matE;
    // Per-material tables, indexed by Material.slot(): fluids mix slots per particle, grains carry one slot each.
    vec4 fluidA[4]; // absorb rgb, FLIP ratio for this step
    vec4 fluidB[4]; // scatter rgb, scatter density
    vec4 fluidC[4]; // film friction, stranded time, glow (emission), viscous damping (1/s, horizontal)
    vec4 grainA[8]; // friction alpha, cohesion, grain radius, brightness
    vec4 grainB[8]; // water drag rate, grains drawn per particle, tint (0xRRGGBB as a float)
    vec4 grainC[8]; // toughness (plastic strain to break, 0 = never breaks), healing under compression (1/strain)
    vec4 mixInfo;   // x = fluid mixing rate (1/s), y = uniform slot or -1, z = 1 for lava (solidifies), w = lava crust coverage
    vec4 carrierInfo;   // x = number of carriers, y = 1 if lone grains stretch the simulated box
    vec4 carrierA[8];   // carriers (grab tool, snowballs): centre (cells), radius (cells)
    vec4 carrierB[8];   // velocity (cells/s), w = 1 to keep moving with it; w = -1: explosion, x = blast speed (cells/s)
} G;

bool isSand() {
    return G.matA.x > 0.5;
}

#define NX G.dims.x
#define NY G.dims.y
#define NZ G.dims.z
#define NCELLS G.dims.w
#define NU G.faces.x
#define NV G.faces.y
#define NW G.faces.z
#define NFACES G.faces.w
#define DT G.gravityDt.w
#define CELL_H G.simB.y

#define CELL_AIR 0u
#define CELL_FLUID 1u
#define CELL_SOLID 2u
// Set on water cells packed with grains of a granular region (sand, gravel...): solid for the water solver, moving with the
// grains' mean velocity taken from the coupling buffer. Bits 8..15 still hold an entity index for entity-occupied cells.
#define CELL_GRANULAR_BIT 0x40000000u
const float COUPLE_FP = 4096.0;

const float FP_SCALE = 65536.0;

// ---- Material mixtures -------------------------------------------------------------------------------------------------
// Materials of one group share a simulation and are told apart by a slot (0..3). A fluid particle's vel.w holds its mixture
// as an exact integer in a float: fraction of slot 1 * 1023 + 1024 * (fraction of slot 2 * 1023); slot 0 is the rest (fluid
// groups use at most 3 slots). A grain's vel.w holds its slot + a random fraction (the grain's look).
// cellMix grids hold per cell: x = particle count, y/z/w = summed slot 1/2/3 fractions * MIX_FP.
// G.mixInfo.y is the slot every particle of the region has, or -1 when the region holds a mixture (CPU-side fast path).
const float MIX_FP = 256.0;
#define C_SLOTS 15
#define SLOT_MIXED_BIT 0x100u

int uniformSlot() {
    return int(G.mixInfo.y);
}

vec4 slotWeights(int slot) {
    return vec4(equal(ivec4(slot), ivec4(0, 1, 2, 3)));
}

vec4 mixWeights(float code) {
    float c = round(max(code, 0.0));
    float hi = floor(c / 1024.0);
    vec2 f = vec2(c - hi * 1024.0, hi) / 1023.0;
    return vec4(max(1.0 - f.x - f.y, 0.0), f, 0.0);
}

vec4 normalizeMix(vec4 w) {
    w = max(w, vec4(0.0));
    float s = w.x + w.y + w.z + w.w;
    return s > 1e-6 ? w / s : vec4(1.0, 0.0, 0.0, 0.0);
}

float mixCodeQ(vec2 q) {
    q = clamp(q, vec2(0.0), vec2(1023.0));
    if (q.x + q.y > 1023.0) {
        q.x = 1023.0 - q.y;
    }
    return q.x + 1024.0 * q.y;
}

float mixCode(vec4 w) {
    return mixCodeQ(round(normalizeMix(w).yz * 1023.0));
}

float slotCode(int slot) {
    return slot == 1 ? 1023.0 : (slot == 2 ? 1023.0 * 1024.0 : 0.0);
}

// Bit of a fluid particle for the region's slot mask: its slot when pure, SLOT_MIXED_BIT when a mixture.
uint fluidSlotBit(float code) {
    float c = round(max(code, 0.0));
    return c == 0.0 ? 1u : (c == 1023.0 ? 2u : (c == 1023.0 * 1024.0 ? 4u : SLOT_MIXED_BIT));
}

// Grain tag = slot + (broken ? 0.5 : 0) + look in [0.001, 0.499): the broken bit survives region merges, unlike the MPM state.
int grainSlot(float tag) {
    return clamp(int(floor(tag)), 0, 7);
}

bool grainBroken(float tag) {
    return fract(tag) >= 0.5;
}

float grainTag(int slot, bool broken, float look) {
    return float(slot) + (broken ? 0.5 : 0.0) + 0.001 + 0.497 * clamp(look, 0.0, 1.0);
}

float grainLook(float tag) {
    return (fract(tag) - (grainBroken(tag) ? 0.5 : 0.0) - 0.001) / 0.497;
}

int dominantSlot(vec4 w) {
    int best = 0;
    float bw = w.x;
    if (w.y > bw) { best = 1; bw = w.y; }
    if (w.z > bw) { best = 2; bw = w.z; }
    if (w.w > bw) { best = 3; }
    return best;
}

vec4 mixFluidA(vec4 w) { return w.x * G.fluidA[0] + w.y * G.fluidA[1] + w.z * G.fluidA[2] + w.w * G.fluidA[3]; }
vec4 mixFluidC(vec4 w) { return w.x * G.fluidC[0] + w.y * G.fluidC[1] + w.z * G.fluidC[2] + w.w * G.fluidC[3]; }

// Scattering adds up by density, so the colour is the density-weighted mean (a little milk turns water milky).
vec4 mixFluidB(vec4 w) {
    vec4 d = w * vec4(G.fluidB[0].w, G.fluidB[1].w, G.fluidB[2].w, G.fluidB[3].w);
    float total = d.x + d.y + d.z + d.w;
    vec3 c = (d.x * G.fluidB[0].rgb + d.y * G.fluidB[1].rgb + d.z * G.fluidB[2].rgb + d.w * G.fluidB[3].rgb) / max(total, 1e-6);
    return vec4(total > 1e-6 ? c : G.fluidB[0].rgb, total);
}

// Summed weights (slot0..3) of a cellMix entry.
vec4 mixSums(ivec4 m) {
    return vec4(float(m.x) * MIX_FP - float(m.y) - float(m.z) - float(m.w), float(m.y), float(m.z), float(m.w));
}

// ---- Carriers (the grab tool, snowballs): material inside a carrier's sphere moves with it as one piece. Particles keep their
// places (they only take on the carrier's velocity); only the outer shell is pulled in so the piece doesn't drift out.
// Explosions: within the radius, material is thrown outwards (and a bit up) at up to the blast speed, fading with distance.
float blastFalloff(int k, vec3 x) {
    float r = length(x - G.carrierA[k].xyz);
    return clamp(1.0 - r / G.carrierA[k].w, 0.0, 1.0);
}

// How strongly x is caught in an explosion this step (0..1), for breaking up breakable solids.
float blastAt(vec3 x) {
    float m = 0.0;
    int n = int(G.carrierInfo.x);
    for (int k = 0; k < n; k++) {
        if (G.carrierB[k].w < 0.0) {
            m = max(m, blastFalloff(k, x));
        }
    }
    return m;
}

float carrierWeight(int k, vec3 x) {
    float r = length(G.carrierA[k].xyz - x);
    return 1.0 - smoothstep(0.8 * G.carrierA[k].w, G.carrierA[k].w, r);
}

// pack: local density relative to resting material (1 = normally packed). The shell pull stops once the piece is packed, so
// holding the tool over a pile doesn't keep cramming material into an ever denser ball.
// Returns the summed weight (0 = not carried).
float applyCarriers(vec3 x, inout vec3 v, float pack) {
    float pull = clamp((1.1 - pack) / 0.3, 0.0, 1.0);
    float total = 0.0;
    int n = int(G.carrierInfo.x);
    for (int k = 0; k < n; k++) {
        if (G.carrierB[k].w < 0.0) {
            float f = blastFalloff(k, x);
            if (f > 0.0) {
                vec3 d = x - G.carrierA[k].xyz;
                float r = length(d);
                vec3 dir = normalize((r > 1e-4 ? d / r : vec3(0.0, 1.0, 0.0)) + vec3(0.0, 0.35, 0.0));
                // Approach the outward speed quickly; never slow down material already flying out faster.
                float want = G.carrierB[k].x * f;
                v += dir * max(want - dot(v, dir), 0.0) * (1.0 - exp(-30.0 * DT));
                total += f;
            }
            continue;
        }
        float w = carrierWeight(k, x);
        if (w <= 0.0) {
            continue;
        }
        vec3 d = G.carrierA[k].xyz - x;
        float r = length(d);
        float shell = max(r - 0.55 * G.carrierA[k].w, 0.0);
        vec3 want = G.carrierB[k].xyz + (r > 1e-4 ? d / r : vec3(0.0)) * shell * 8.0 * pull;
        // Held up: undo this step's gravity (already in v), or the piece sags to the bottom of the sphere and flattens.
        v -= G.gravityDt.xyz * DT * w;
        v = mix(v, want, w * (1.0 - exp(-40.0 * DT)));
        total += w;
    }
    return min(total, 1.0);
}

vec2 signNotZero(vec2 v) {
    return vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
}

vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    return n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * signNotZero(n.xy);
}

vec3 octDecode(vec2 e) {
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * signNotZero(n.xy);
    }
    return normalize(n);
}

// Vector component access by a runtime axis index without v[a] syntax. Dynamic indexing of vectors (especially writes
// into inout/local vec3s) is compiled differently between drivers; these select-based helpers behave identically everywhere.
bvec3 axisSel(int a) {
    return equal(ivec3(a), ivec3(0, 1, 2));
}

float getAxis(vec3 v, int a) {
    return a == 0 ? v.x : (a == 1 ? v.y : v.z);
}

vec3 setAxis(vec3 v, int a, float s) {
    return mix(v, vec3(s), axisSel(a));
}

int cellIndex(ivec3 c) {
    return c.x + NX * (c.y + NY * c.z);
}

bool inGrid(ivec3 c) {
    return c.x >= 0 && c.y >= 0 && c.z >= 0 && c.x < NX && c.y < NY && c.z < NZ;
}

ivec3 cellCoord(int i) {
    return ivec3(i % NX, (i / NX) % NY, i / (NX * NY));
}

ivec3 faceDims(int comp) {
    return ivec3(NX, NY, NZ) + ivec3(comp == 0 ? 1 : 0, comp == 1 ? 1 : 0, comp == 2 ? 1 : 0);
}

int faceBase(int comp) {
    return comp == 0 ? 0 : (comp == 1 ? NU : NU + NV);
}

int faceIndex(int comp, ivec3 f) {
    ivec3 d = faceDims(comp);
    return faceBase(comp) + f.x + d.x * (f.y + d.y * f.z);
}

vec3 faceOffset(int comp) {
    return vec3(comp == 0 ? 0.0 : 0.5, comp == 1 ? 0.0 : 0.5, comp == 2 ? 0.0 : 0.5);
}

ivec3 axisVec(int comp) {
    return ivec3(comp == 0 ? 1 : 0, comp == 1 ? 1 : 0, comp == 2 ? 1 : 0);
}

void decodeFace(int i, out int comp, out ivec3 f) {
    if (i < NU) {
        comp = 0;
    } else if (i < NU + NV) {
        comp = 1;
        i -= NU;
    } else {
        comp = 2;
        i -= NU + NV;
    }
    ivec3 d = faceDims(comp);
    f = ivec3(i % d.x, (i / d.x) % d.y, i / (d.x * d.y));
}

ivec3 boxDims() {
    return G.boxMax.xyz - G.boxMin.xyz;
}

bool inBox(ivec3 c) {
    return all(greaterThanEqual(c, G.boxMin.xyz)) && all(lessThan(c, G.boxMax.xyz));
}

bool inInnerBox(vec3 x) {
    return all(greaterThanEqual(x, vec3(G.boxMin.xyz) + 2.0)) && all(lessThan(x, vec3(G.boxMax.xyz) - 2.0));
}

ivec3 boxCell(int t) {
    ivec3 d = boxDims();
    return G.boxMin.xyz + ivec3(t % d.x, (t / d.x) % d.y, t / (d.x * d.y));
}

bool faceInBox(int comp, ivec3 f) {
    return all(greaterThanEqual(f, G.boxMin.xyz)) && all(lessThan(f, G.boxMax.xyz + axisVec(comp)));
}

bool boxFace(int t, out int comp, out ivec3 f) {
    ivec3 d = boxDims();
    int rest = t;
    for (int c = 0; c < 3; c++) {
        ivec3 fd = d + axisVec(c);
        int n = fd.x * fd.y * fd.z;
        if (rest < n) {
            comp = c;
            f = G.boxMin.xyz + ivec3(rest % fd.x, (rest / fd.x) % fd.y, rest / (fd.x * fd.y));
            return true;
        }
        rest -= n;
    }
    comp = 3;
    f = ivec3(0);
    return false;
}

int numEntities() {
    return G.caps.z;
}

vec3 entMin(int e) { return G.ent[e * 3].xyz; }
vec3 entMax(int e) { return G.ent[e * 3 + 1].xyz; }
vec3 entVel(int e) { return G.ent[e * 3 + 2].xyz; }

int entityAt(vec3 p) {
    for (int e = 0; e < numEntities(); e++) {
        if (all(greaterThanEqual(p, entMin(e))) && all(lessThanEqual(p, entMax(e)))) {
            return e;
        }
    }
    return -1;
}

uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

float rand(inout uint state) {
    state = hash(state + 0x9e3779b9u);
    return float(state >> 8) * (1.0 / 16777216.0);
}

vec3 randInSphere(inout uint state) {
    for (int i = 0; i < 8; i++) {
        vec3 p = vec3(rand(state), rand(state), rand(state)) * 2.0 - 1.0;
        if (dot(p, p) <= 1.0) {
            return p;
        }
    }
    return vec3(0.0);
}

float clampPhi(float v, float lo, float hi) {
    return clamp((v - lo) / max(hi - lo, 1e-6), 0.0, 1.0);
}

int fineIndex(ivec3 p) {
    return p.x + G.fine.x * (p.y + G.fine.y * p.z);
}

bool inFine(ivec3 p) {
    return all(greaterThanEqual(p, G.fineBoxMin.xyz)) && all(lessThan(p, G.fineBoxMax.xyz));
}

uint packNormal(vec3 n, bool flag) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    uvec2 q = uvec2(clamp(e * 0.5 + 0.5, 0.0, 1.0) * 32767.0 + 0.5);
    return q.x | (q.y << 15) | (flag ? 0x40000000u : 0u);
}

vec3 unpackNormal(uint v, out bool flag) {
    flag = (v & 0x40000000u) != 0u;
    vec2 e = vec2(float(v & 0x7FFFu), float((v >> 15) & 0x7FFFu)) / 32767.0 * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}
