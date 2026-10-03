#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec4 vertexColor;
in vec2 localPos;
flat in vec2 halfSize;
flat in vec4 radii;       // top-left, top-right, bottom-right, bottom-left
flat in float borderWidth;
flat in vec4 sideWidths;  // top, right, bottom, left
flat in int side;

out vec4 fragColor;

// Signed distance to a rounded box centred at the origin (y points down, like GUI coordinates).
float roundedBox(vec2 p, vec2 b, vec4 r) {
    float rad = p.x < 0.0 ? (p.y < 0.0 ? r.x : r.w) : (p.y < 0.0 ? r.y : r.z);
    vec2 q = abs(p) - b + rad;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rad;
}

void main() {
    float d = roundedBox(localPos, halfSize, radii);
    float aa = max(fwidth(d), 0.0001); // one screen pixel, for anti-aliasing
    float coverage;
    if (side >= 0) {
        // One side of a border with different sides, like browsers: the ring between the outer edge and the inner
        // edge (inset by each side's width), split at the corners on the line from the outer to the inner corner.
        vec4 w = sideWidths;
        vec2 innerMin = -halfSize + vec2(w.w, w.x);
        vec2 innerMax = halfSize - vec2(w.y, w.z);
        vec2 innerHalf = (innerMax - innerMin) * 0.5;
        float inner = 0.0;
        if (innerHalf.x > 0.0 && innerHalf.y > 0.0) {
            vec4 innerRadii = max(radii - vec4(max(w.w, w.x), max(w.y, w.x), max(w.y, w.z), max(w.w, w.z)), 0.0);
            float di = roundedBox(localPos - (innerMin + innerMax) * 0.5, innerHalf, innerRadii);
            inner = clamp(0.5 - di / max(fwidth(di), 0.0001), 0.0, 1.0);
        }
        coverage = max(clamp(0.5 - d / aa, 0.0, 1.0) - inner, 0.0);
        // The side whose edge is nearest relative to its width owns the point
        float far = 1e9;
        vec4 rel = vec4(
            w.x > 0.0 ? (localPos.y + halfSize.y) / w.x : far,
            w.y > 0.0 ? (halfSize.x - localPos.x) / w.y : far,
            w.z > 0.0 ? (halfSize.y - localPos.y) / w.z : far,
            w.w > 0.0 ? (localPos.x + halfSize.x) / w.w : far
        );
        int owner = 0;
        float best = rel.x;
        if (rel.y < best) { best = rel.y; owner = 1; }
        if (rel.z < best) { best = rel.z; owner = 2; }
        if (rel.w < best) { owner = 3; }
        if (owner != side) coverage = 0.0;
    } else if (borderWidth >= 0.0) {
        // Background: everything inside the inner border edge.
        coverage = clamp(0.5 - (d + borderWidth) / aa, 0.0, 1.0);
    } else {
        // Border ring between the outer edge and the inner edge.
        float outer = clamp(0.5 - d / aa, 0.0, 1.0);
        float inner = clamp(0.5 - (d - borderWidth) / aa, 0.0, 1.0);
        coverage = outer - inner;
    }
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * coverage);
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}
