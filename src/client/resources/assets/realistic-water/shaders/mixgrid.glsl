// Needs: ivec4 cellMix[] (count, slot1..3 sums) over the region's cells.

vec4 mixSumsAt(ivec3 c) {
    return inBox(c) ? mixSums(cellMix[cellIndex(c)]) : vec4(0.0);
}

// Mixture around a point (cell units): trilinear over cell sums, widening to the 3x3x3 neighbourhood when the 2x2x2 one is
// empty (surfaces sit between full and empty cells). Uniform regions skip the grid entirely.
vec4 mixAt(vec3 x) {
    if (uniformSlot() >= 0) {
        return slotWeights(uniformSlot());
    }
    vec3 g = x - 0.5;
    ivec3 b = ivec3(floor(g));
    vec3 f = g - vec3(b);
    vec4 s = vec4(0.0);
    for (int k = 0; k < 8; k++) {
        ivec3 o = ivec3(k & 1, (k >> 1) & 1, k >> 2);
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y) * (o.z == 1 ? f.z : 1.0 - f.z);
        s += w * mixSumsAt(b + o);
    }
    if (s.x + s.y + s.z + s.w < 1e-3) {
        ivec3 c = ivec3(floor(x));
        for (int k = 0; k < 27; k++) {
            s += mixSumsAt(c + ivec3(k % 3, (k / 3) % 3, k / 9) - 1);
        }
    }
    return normalizeMix(s);
}
