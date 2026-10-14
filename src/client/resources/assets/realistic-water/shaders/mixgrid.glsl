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

// Granular regions: cellMix y = grain counts of slots 0..3, z = slots 4..7, 8 bits each.
uint grainCount(ivec4 m, int slot) {
    uint w = uint(slot < 4 ? m.y : m.z);
    return (w >> (8u * uint(slot & 3))) & 0xFFu;
}

// Water drag of the grains around a cell: count-weighted mean of their materials' rates.
float grainDragAt(ivec3 c) {
    if (uniformSlot() >= 0) {
        return G.grainB[uniformSlot()].x;
    }
    float n = 0.0;
    float d = 0.0;
    for (int k = 0; k < 27 && (k == 0 || n == 0.0 || k < 7); k++) {
        // own cell first, then its 6 neighbours, then the rest only if still empty
        ivec3 o = k == 0 ? ivec3(0) : (k < 7 ? ivec3(k == 1 ? 1 : (k == 2 ? -1 : 0), k == 3 ? 1 : (k == 4 ? -1 : 0), k == 5 ? 1 : (k == 6 ? -1 : 0))
            : ivec3(k % 3, (k / 3) % 3, k / 9) - 1);
        ivec3 q = c + o;
        if (!inBox(q)) {
            continue;
        }
        ivec4 m = cellMix[cellIndex(q)];
        for (int s = 0; s < 8; s++) {
            float cnt = float(grainCount(m, s));
            n += cnt;
            d += cnt * G.grainB[s].x;
        }
    }
    return n > 0.0 ? d / n : G.grainB[0].x;
}
