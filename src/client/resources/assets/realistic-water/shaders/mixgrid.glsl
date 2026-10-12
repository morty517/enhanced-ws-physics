// Needs: ivec4 cellMix[] (count, slot1 sum, slot2 sum, -) over the region's cells.

vec3 mixSumsAt(ivec3 c) {
    return inBox(c) ? mixSums(cellMix[cellIndex(c)]) : vec3(0.0);
}

// Mixture around a point (cell units): trilinear over cell sums, widening to the 3x3x3 neighbourhood when the 2x2x2 one is
// empty (surfaces sit between full and empty cells).
vec3 mixAt(vec3 x) {
    vec3 g = x - 0.5;
    ivec3 b = ivec3(floor(g));
    vec3 f = g - vec3(b);
    vec3 s = vec3(0.0);
    for (int k = 0; k < 8; k++) {
        ivec3 o = ivec3(k & 1, (k >> 1) & 1, k >> 2);
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y) * (o.z == 1 ? f.z : 1.0 - f.z);
        s += w * mixSumsAt(b + o);
    }
    if (s.x + s.y + s.z < 1e-3) {
        ivec3 c = ivec3(floor(x));
        for (int k = 0; k < 27; k++) {
            s += mixSumsAt(c + ivec3(k % 3, (k / 3) % 3, k / 9) - 1);
        }
    }
    return normalizeMix(s);
}
