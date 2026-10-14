// Workgroup-level stream compaction for the particle advection passes (g2p / mpm_g2p).
//
// The Vulkan original used GL_KHR_shader_subgroup_* (subgroupAdd/ExclusiveAdd/Max/Min/Elect/BroadcastFirst) to give each
// surviving particle a slot in the destination buffer with one global atomic per subgroup. Subgroup ops are not reliably
// available in desktop OpenGL drivers, so this does the same per workgroup through shared memory: one global atomic per
// 256 invocations instead of per 32/64. Every invocation of the workgroup must call these in uniform control flow
// (no early return before them), because they contain barrier().
//
// Requires a `uint counters[]` storage buffer to be declared before inclusion.

shared uint cmpCount;
shared uint cmpBase;
shared uint cmpMaxSpeed;
shared uint cmpMaxSpeedAll;
shared uint cmpAwake;
shared uint cmpLo[3];
shared uint cmpHi[3];

void compactBegin() {
    if (gl_LocalInvocationIndex == 0u) {
        cmpCount = 0u;
        cmpMaxSpeed = 0u;
        cmpMaxSpeedAll = 0u;
        cmpAwake = 0u;
        cmpLo[0] = 0xFFFFFFFFu;
        cmpLo[1] = 0xFFFFFFFFu;
        cmpLo[2] = 0xFFFFFFFFu;
        cmpHi[0] = 0u;
        cmpHi[1] = 0u;
        cmpHi[2] = 0u;
    }
    barrier();
}

// Reserves an output slot for every invocation with alive == true. Returns the global slot index (0xFFFFFFFF if not alive).
// Speeds must be >= 0 (float bits are compared as uint). counters[6] gets the largest speed of the bulk of the material (what
// the time step has to follow), counters[7] the largest of all particles, spray included (what the window has to follow).
uint compactReserve(bool alive, float bulkSpeed, float speed, bool awake, uint counterIndex) {
    uint local = 0u;
    if (alive) {
        local = atomicAdd(cmpCount, 1u);
        atomicMax(cmpMaxSpeed, floatBitsToUint(bulkSpeed));
        atomicMax(cmpMaxSpeedAll, floatBitsToUint(speed));
        if (awake) {
            atomicAdd(cmpAwake, 1u);
        }
    }
    barrier();
    if (gl_LocalInvocationIndex == 0u && cmpCount > 0u) {
        cmpBase = atomicAdd(counters[counterIndex], cmpCount);
        atomicMax(counters[6], cmpMaxSpeed);
        atomicMax(counters[7], cmpMaxSpeedAll);
    }
    barrier();
    return alive ? cmpBase + local : 0xFFFFFFFFu;
}

// Workgroup total of the `awake` flags passed to compactReserve (valid after it).
uint compactAwake() {
    return cmpAwake;
}

// Grows the region's particle AABB (counters[8..13], biased cell coords) by every cell passed with wrote == true.
void compactBounds(bool wrote, uvec3 cell) {
    if (wrote) {
        atomicMin(cmpLo[0], cell.x);
        atomicMin(cmpLo[1], cell.y);
        atomicMin(cmpLo[2], cell.z);
        atomicMax(cmpHi[0], cell.x);
        atomicMax(cmpHi[1], cell.y);
        atomicMax(cmpHi[2], cell.z);
    }
    barrier();
    if (gl_LocalInvocationIndex == 0u && cmpLo[0] <= cmpHi[0]) {
        atomicMin(counters[8], cmpLo[0]);
        atomicMin(counters[9], cmpLo[1]);
        atomicMin(counters[10], cmpLo[2]);
        atomicMax(counters[11], cmpHi[0]);
        atomicMax(counters[12], cmpHi[1]);
        atomicMax(counters[13], cmpHi[2]);
    }
}
